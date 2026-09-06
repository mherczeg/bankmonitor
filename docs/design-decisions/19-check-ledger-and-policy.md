# Ticket 19 — the Check Ledger and the decision it drives

§8's orchestration, built: one row per Check a Transfer requires, written in the
Transfer's own transaction, and a pure function from those rows to *settle,
reject or wait*. Nothing advances a Transfer yet — ticket 20 is the caller — so
what this ticket had to settle was **the shape of the record and the shape of the
answer**, which every later transition then has to live with.

Touches §8 of the [initial decisions](00-initial-decisions.md) and contradicts one
detail of it. It also declines the per-row deadline §14 asks for — but declining a
column is not amending the section, and §14 is amended by ticket 23 rather than by
this one. The closing section says why.

---

## An unanswered Check has no Verdict, rather than a `PENDING` one

§8 sketched the table with a `status` column carrying `PENDING`, `APPROVED` or
`REJECTED`. The table has a nullable `verdict` column instead, and `Verdict` has
exactly the two constants
[CONTEXT.md](../../CONTEXT.md) defines: *approved or rejected*.

The argument is that a three-valued column makes "nobody has answered" one of the
answers, and then every reader of the ledger has to know which of the three is
not really a Verdict. `TransferStatus` carries `PENDING` for the opposite reason
and the contrast is the point: a Transfer genuinely *is* in a pending state, with
funds reserved and a deadline running. Nothing is in the state of having given
the null answer.

It costs nothing at the two places it could have. The outstanding-row guard
ticket 20 needs is `where verdict is null` rather than `where status =
'PENDING'`, which is the same conditional update. Ticket 22's screen wants
exactly this distinction — outstanding rows against answered ones — and gets it
from the column being absent rather than from a constant it has to interpret.

**Rejected — `Verdict.PENDING` and a `not null` column.** It matches §8's sketch
and it reads fine in the table, and it would have made `decide` a comparison
against a constant that means "no comparison has been made yet".

---

## `decide` is a function over rows, and it refuses an empty ledger

`LedgerDecision.decide(Collection<CheckLedgerEntry>)` asks two questions in one
order that matters: *has anything rejected*, then *is anything outstanding*. The
second is only meaningful because the first has already excluded every rejection,
so "answered and not rejected" is approved without a third comparison.

Rejection is asked first because **a rejection wins immediately.** A ledger of one
rejection and three unanswered rows is `REJECT`, not `WAIT`: nothing the
outstanding Checks could say would revive the Transfer, and waiting for them holds
an operator's funds against an outcome that is already decided. The mutation that
swaps the two questions is in the table below, and one test catches it.

**The empty ledger is refused rather than settled**, which is the one place the
rule as stated in the ticket gives a dangerous answer. Nothing outstanding and
nothing rejected is literally true of no rows at all, so the natural
implementation settles a Transfer that nobody checked. That input cannot arise —
the rows are written with the Transfer — so the only way to be handed one is to
have loaded the wrong Transfer's ledger, and answering that with "move the money"
is the worst available guess. `IllegalArgumentException`, and a test.

### Why it is not a method on a service

The ticket says keeping `decide` free of I/O is a design instruction and not a
testing one, and the difference showed up immediately in what the tests cost. Six
ledgers, one line of setup each, no Spring context and no database. The same
three lines inside the service that records a Verdict would only be reachable
through two Accounts, a Transfer and four rows written across three slices — and
the mixed-verdict case, which is the one most likely to be wrong, would be the one
most expensive to write.

`LedgerDecision` is an enum with a static factory rather than a sealed interface,
because none of the three outcomes carries data. `ConversionResult` in `fx` is
sealed because its outcomes do (a converted amount, a rounded-to-zero refusal);
here a sealed hierarchy would be three empty records and a `switch` that reads
identically.

---

## The ledger is opened by mandatory propagation, not by convention

`CheckLedger.openFor` is `@Transactional(propagation = MANDATORY)`, on
`AccountLocking`'s precedent. It cannot start a transaction of its own, so the
only way to call it is from inside one that is already writing the Transfer.

The failure it forecloses is quiet. A ledger opened after the Transfer's
transaction commits leaves a window — and, if the second commit fails, a
permanent state — in which a `PENDING` Transfer has an empty ledger. Such a
Transfer has no outstanding Check for anybody to answer, so nothing settles it and
nothing rejects it; it sits holding reserved funds until a person notices, and
"why is this stuck" has no answer to find. That is precisely the question the
Check Ledger exists to make answerable, so the ledger being late is the ledger
failing at its only job.

`FundsReservation` is where the transaction is opened, and the ledger is written
last inside it because the rows point at the Transfer's generated ID.

### Being late is one way there; requiring nothing is the other

Mandatory propagation closes the timing hole and leaves the degenerate one wide
open. `openFor` writes one row per Check the policy names, so a policy that names
none writes nothing, returns normally, and lets the caller commit exactly the
stuck Transfer the paragraph above is about. Every test in the slice stays green
while it happens: the reservation tests assert the rows a two-Check policy
produces, and the ArchUnit rule only ever spoke about a *second* writer.

So `openFor` refuses an empty required-set. The guard is there rather than in
`CheckPolicy` because a policy is entitled to answer the question it was asked —
"which Checks does this Transfer require" has an empty answer in principle, and
some later conditional rule may well compute one. What is not permissible is
*acting* on that answer by writing no ledger, and `openFor` is the only place that
sees the answer and the Transfer together.

**This is the complement to `decide` refusing an empty ledger, not a replacement
for it.** `decide` is the wrong end to defend alone: by the time it is handed no
rows, the Transfer holding an operator's funds already exists and has already been
committed. The two guards catch the same impossibility on the way in and on the
way out, and only the first prevents it.

---

## "Impossible to create" needed a second test to be a claim about the design

The checkbox asks that a Transfer with no ledger rows be *impossible*, and a test
showing that `FundsReservation` writes both is not that. It is a claim about one
path, and a second path would not make it false: a settlement retry, a fixture, a
back-office correction tool — anything that saved a `Transfer` of its own would
produce the empty-ledger row above, and every test in the slice would stay green.

So the second test is about the write rather than about the ledger:
`NothingButTheReservationCreatesATransferTest` holds that `TransferRepository` has
exactly one dependent in the whole application. The compiler already stops another
*slice* — §30 keeps the repository package-private — and this covers the inside of
`transfers`, which is where a second writer would actually be added.

It matches the repository **type** rather than a call to `save`, because tickets
15, 20 and 23 all add query methods to that interface and a rule naming `save`
would go on passing while the class that holds the repository grew a second write.

Neither test is worth much alone, and the Javadoc on each says so: the ArchUnit
rule stays green if the `openFor` call is deleted, and the booted test stays green
if a second writer appears. Together they are the checkbox.

---

## `check` is reserved in SQL, so the column is `required_check`

The obvious column name does not survive contact with the database: `check` is a
reserved word, and a `create table` declaring one fails to parse. Quoting it in
the DDL and again in `@Column(name = "\"check\"")` would work and would put a
quoted identifier in every hand-written query from here on.

The field is `requiredCheck`, which Hibernate's implicit naming turns into
`required_check`. The migration README now carries the trap beside the two it
already records.

The verdict constraint is the shape that migration README's warning is about:
`verdict is null or verdict = any (array['APPROVED', 'REJECTED'])`. The `or` here
is with an `is null` rather than with a second equality, which is what keeps it out
of the constant-`in`-list folding that breaks every insert into the table — and
the tests below establish both halves, that valid rows go in and that an invalid
Verdict does not.

---

## The finding: a constraint test that passed for the wrong reason

`refusesAVerdictTheDomainDoesNotHave` was first written as an `INSERT` of
`('FRAUD', 'MAYBE')` against a Transfer that already had a ledger. It was green.
It was still green when the constraint was widened to `array['APPROVED',
'REJECTED', 'MAYBE']` — the insert was being refused by
`check_ledger_one_row_per_check`, and the constraint under test was never
consulted.

It is now an `UPDATE`, which is also the shape ticket 20 will write a Verdict in,
and the uniqueness rule it was accidentally testing has a test of its own. The
mutation that found it is in the table below.

---

## Every claim is falsifiable, and each was falsified

| mutation to production code | test that turns red |
|---|---|
| delete `ledger.openFor(requested)` from `FundsReservation.reserve` | `opensOneOutstandingRowPerRequiredCheck`, `opensASeparateLedgerForEachTransfer` |
| `@Transactional` instead of `@Transactional(MANDATORY)` on `openFor` | `refusesToOpenALedgerWithNoTransaction` |
| ask "is anything outstanding" before "has anything rejected" in `decide` | `rejectsWhileAnotherCheckIsStillOutstanding` |
| widen the verdict constraint to accept `'MAYBE'` | `refusesAVerdictTheDomainDoesNotHave` |
| drop `MANUAL_APPROVAL` from `CheckPolicy.requiredFor` | `opensOneOutstandingRowPerRequiredCheck`, `opensASeparateLedgerForEachTransfer` |
| delete the empty-required-set refusal from `openFor` | `refusesToOpenALedgerWithNoChecksInIt` |
| return `EnumSet.noneOf(Check.class)` from `CheckPolicy.requiredFor` | all four writing tests in `EveryTransferOpensItsCheckLedgerTest` |

Each was applied, run, and reverted. The fourth row is the finding above: it was
green under its mutation until the test was rewritten.

The last two rows are one claim from both sides. The fifth is the ordinary
falsification — the guard was written after a red test, and deleting it turns that
test red again. The sixth is the one that says the guard is *reachable*: with a
policy that genuinely requires nothing, `reserve` fails with `a transfer's check
ledger is never empty` instead of committing a Transfer nobody will ever check. A
unit test against a stubbed policy could not have shown that, because a guard
wired to nothing passes it just as greenly.

The ArchUnit rule is falsified the way `ModuleBoundariesHoldTest` falsifies its
own — by a fixture outside the scanned packages that breaks it on purpose, so a
prohibition that has quietly stopped matching anything cannot pass as one that
holds.

---

## Two smaller shapes, and why each is the way it is

**`CheckPolicy.requiredFor` takes the `Transfer` and does not read it.** Every
Transfer requires both Checks today, so the parameter is unused — and it is still
the seam the ticket asks for. A policy that cannot see the Transfer cannot grow the
first rule that depends on one ("manual approval above ten thousand", "fraud
screening only across Currencies"), and this is the ticket that gets to choose the
signature while `CheckPolicy` is package-private with one caller. Deliberately not
`EnumSet.allOf(Check.class)`, which is shorter and would silently apply the first
conditional Check to every Transfer ever written: what Checks *exist* and what a
given Transfer *requires* are two questions.

**`CheckLedgerEntry` has a constructor production never calls.** `outstanding` is
how a row comes into being; the three-argument constructor can express a row with
a Verdict in it, and only the tests use it. The distinction that makes it
legitimate is that constructing a row in a state is not the same operation as
*transitioning* one into it — the transition is the thing with a race to lose, and
it stays a guarded update with no setter, on `IdempotencyRecord`'s precedent. A
function specified over ledger rows has to be given rows in every state a row can
hold, and a test-only factory would be the same surface under another name.

---

## What this ticket deliberately did not build

- **Any read of the ledger.** `decide` has no production caller until ticket 20,
  and the repository therefore has one method. The query that loads a Transfer's
  rows, and the update guarded on a row still being unanswered, arrive with the
  ticket that has a question to ask of them.
- **A deadline.** §14 says each check row carries one; [ticket
  23](../../.scratch/global-payment-service/issues/23-expiry-reaper.md) puts it on
  the Transfer instead, and ticket 23 is right — expiry is a property of the
  Transfer's whole lifecycle, the reaper scans Transfers, and §15 ties the same
  clock to the Exchange Rate quote, which is also the Transfer's. A per-row
  deadline would be four columns answering one question. **§14 is amended by ticket
  23, not by this one**; what this ticket did was decline to add the column §14
  asks for, and say why.
- **The Verdict endpoint, and settlement.** Tickets 20 and 21. `decide` returning
  `SETTLE` moves no money here, and `LedgerDecision` is package-private until
  something outside `checks` needs to name it.
- **Anything on the wire.** The ledger reaches the single-Transfer response in
  ticket 22, which is also where the two enums first need to be public.
