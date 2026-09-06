# Ticket 20 — recording a Verdict, and settlement

§7's transitions, built: `recordVerdict(transferId, check, verdict)` answers one
Check, asks [ticket 19](19-check-ledger-and-policy.md)'s `decide` what the whole
ledger then supports, and settles or rejects the Transfer on that answer. It is
the seam every adapter sits over — ticket 21's HTTP callback today, a broker
consumer later — so nothing here knows how the Verdict arrived.

Touches §6, §7, §8 and §13 of the [initial decisions](00-initial-decisions.md),
and **corrects one sentence of §8 in place**. The first section is why.

---

## The conditional update is not what makes concurrent Verdicts safe

§8 closes with *"Verdicts must be idempotent — a check reporting `APPROVED` twice
must not advance anything twice. Same conditional update as §5."* The ticket
repeats it: `UPDATE … WHERE status = ?` with a rows-affected check, "so exactly
one caller wins".

The guard is real and it is built. It is not what closes the race, and the
failure it misses is worse than the one it catches:

- Thread A writes the `FRAUD` row; thread B writes the `MANUAL_APPROVAL` row.
- A reads the ledger. B's row is uncommitted and therefore invisible, so A sees
  one Check answered and one outstanding, and `decide` returns `WAIT`.
- B reads the ledger and sees the mirror image. Also `WAIT`.
- Both commit. **The Transfer is `PENDING` for ever**, holding the operator's
  funds, against a fully approved ledger. Nothing ever runs the conditional
  update.

No guard on the `UPDATE` helps, because neither transaction reaches one. The
failure is not "advanced twice", which is what a guard prevents; it is "advanced
never", which only serialisation prevents.

So **`recordVerdict` takes a pessimistic row lock on the Transfer before it
touches the ledger** — `@Lock(PESSIMISTIC_WRITE)` on
`TransferTransitions.findAndLockById`, which is the same `select … for update` §6
already uses on Accounts. Whichever thread arrives second reads a ledger the
first has committed, sees every Check answered, and settles.

**Rejected — locking the ledger rows.** `select … for update` over
`check_ledger` after writing one's own row deadlocks by construction: A holds the
`FRAUD` row and wants `MANUAL_APPROVAL`, B holds the reverse.

**Rejected — letting the Account locks serialise it.** Two Verdicts on one
Transfer contend on the same two Accounts, so the existing locks would in fact
serialise them and no new lock would be needed. But the Account locks are taken
only on the settling and rejecting paths, and taking them earlier would mean a
write lock on two hot Account rows for *every* Verdict including the waiting
ones — contention scaling with Account activity rather than with Transfer
activity, which is backwards for a design whose whole point is that most Verdicts
do nothing.

Lock order is therefore **Transfer first, then Accounts ascending**. That is
acyclic against `FundsReservation`, which takes Account locks only and never
waits on a Transfer row that already exists, and it is the order ticket 23's
reaper will want.

### The guard is still written, and here is its honest job

`advanceFromPending` is `UPDATE … WHERE id = ? AND status = 'PENDING'` returning
a row count, exactly as the ticket asks. Under the row lock **it cannot fail** —
and the mutation table below records that removing the `AND status = 'PENDING'`
clause leaves all 214 tests green.

That is not an argument for deleting it. What it buys is that the transition
carries its own precondition rather than trusting its caller to have checked one:
a future caller that reached this method without the lock fails loudly instead of
settling a Transfer somebody else already settled. It is the same shape as the
idempotency record's claim (§5) and the expiry reaper's sweep (§14) — one idea
used three times rather than three mechanisms.

What it is **not** is the thing that makes the ticket's checkbox true, and saying
otherwise in a design record would be the kind of claim that reads as covered and
is not.

---

## A late Verdict needs its own refusal, which the guard cannot give it

Checkbox 5 — *a Verdict on an already-terminal Transfer is refused* — cannot be
served by the rows-affected check either, for a reason that is easy to miss.

A Check approving a Transfer that another Check has already rejected produces a
ledger that decides `REJECT` all over again. The transition to `REJECTED` has
already happened, so the guarded update matches nothing — and on the "matched
nothing, therefore somebody else won" reading, that is a *success*. The Verdict
would be written silently into the ledger of a dead Transfer and the caller would
be told nothing went wrong.

So the status is read **under the lock, before anything is written**, and a
Transfer that is not `PENDING` is refused with `TransferNotPendingException`.
Once ticket 23 expires Transfers this stops being a corner case: an `EXPIRED`
Transfer still has unanswered rows, so every late Verdict lands here.

The exception carries the status it found, not only the ID, because "too late" is
not an answer on its own — a Check service that finds `SETTLED` has learnt its
report landed, and one that finds `EXPIRED` has learnt it did not. Ticket 21
chooses the status code and the URN over that field.

### A redelivery onto a settled Transfer is refused rather than absorbed

This is the one place two of the ticket's own checkboxes overlap, so it is worth
being explicit. A Check service delivers at least once, so the very Verdict that
settled a Transfer *will* arrive again — and it meets the refusal above rather
than the ledger's guard.

The substantive claim of checkbox 4 holds: the money moved once, which the test
asserts against both balances. What the redelivering caller gets is
`TransferNotPendingException` carrying `SETTLED`, which is a truthful and useful
answer — its report landed, and here is what it did.

**Rejected — absorbing the redelivery** by reading the ledger before the refusal,
finding this Check already carries this Verdict, and returning the current status
as though nothing had happened. It is friendlier, and it buys that friendliness
with a longer critical section under the row lock and a second reading path into
the ledger whose only purpose is to decide whether to refuse.

Because this was a judgement call between two defensible answers rather than
something the design already implied, it was put as a question and answered:
**the refusal stands.** So ticket 21 inherits the shape and chooses only the
status code for it. If a retrying Check service later turns out to be badly
served by an error, absorbing is still a change to one branch, and
`docs/deferred.md` is where it is written down.

A redelivery that arrives while the Transfer is **still `PENDING`** takes the
other path entirely: it reaches the ledger, whose update is guarded on the row
still being unanswered, matches nothing, and the caller gets back the decision the
ledger already supported. That is ticket 19's mechanism working, and ticket 20's
tests are what show the guard is real in SQL rather than only in the in-memory
fake the unit test uses.

---

## `TransferTransitions`, and the axis the rule is actually about

Ticket 19 left `NothingButTheReservationCreatesATransferTest`: *no class but
`FundsReservation` may depend on the interface declaring `save`*, because a
Transfer written anywhere else would be `PENDING` against an empty ledger.

The rule's own Javadoc claimed no later ticket would strain it — that matching the
repository *type* rather than a call to `save` "survives the query methods tickets
15, 20 and 23 will add to it". That is half right, and the wrong half was load
bearing: it survives methods being **added** to the interface, but not a second
class **calling** them, and reading a Transfer and advancing one are exactly that.

Ticket 15 hit it first and split the reads onto `TransferQueries`. Ticket 20 hit
it again from the other side, and the second time carries the information the
first could not: **the axis is not read against write.** Ticket 15 recorded the
expectation that the transition's conditional update would land beside `save`
because it *is* a write, and that is the one place it could not go —
`VerdictRecording` holding the write side would hold `save` along with it. What
the rule protects is not that a method writes but that `save` is reachable.

**Chosen: `TransferTransitions`**, a package-private interface holding
`findAndLockById` and `advanceFromPending`. `TransferRepository` still declares
`save` and nothing else, which is what lets the rule name it outright and keep its
exact shape, strength and falsification fixture.

So the distinction the rule was always about is **restricted against
unrestricted**. Creating a Transfer is restricted, because one created outside the
reservation is the empty-ledger state the whole design is arranged to prevent.
Reading one, and advancing one by a guarded update, are not restricted at all —
the guard travels with the statement, so a second caller cannot make the
transition twice however it got there.

The lock and the guarded update stay on one interface together because they are a
single argument, and reading either alone gets that argument backwards: the lock
is what makes concurrent Verdicts safe, and the guard is not.

**Rejected — putting the transition on `TransferQueries`.** It is two interfaces
rather than three, and it buys that by having something named for reads declare a
`@Modifying UPDATE`. The names are the enforcement mechanism here; a name that has
to be read past is worth less than the interface it saves.

**Rejected — naming `save` in the rule.** Ticket 19 considered and rejected
exactly this, because a class that holds the repository is one edit away from
calling the method. Re-adopting it here would be undoing that ticket's reasoning
to avoid an interface.

**Rejected — an allow-list of classes permitted to hold the repository.** It is a
structural claim degraded into a list that every later ticket appends to, and a
list that grows by one per ticket is a list nobody reads.

**Rejected — a second repository per caller.** Interfaces per caller do not scale
past this ticket, and the thing being protected is the write, not the caller. The
split that did happen is by *kind of access*, of which there are three and are not
expected to be more: ticket 23's overdue scan is a read and its claim on the
Transfer it reaps is a transition.

---

## `Account.settle` moves both figures, and that is why it is one method

§13's two-field model means settlement has two halves: the balance falls by the
amount **and** the Reserved Amount falls with it, because the reservation is
consumed rather than left behind. `Account.settle(Money)` does both.

A debit without the matching release would pass every balance assertion and leave
the Account carrying a reservation for a Transfer that is finished — so every
later overdraft check would test against an Available Balance short by that
amount, for ever. That is the bug the ticket's wording exists to prevent, and
splitting the two halves across two calls is how it would get reintroduced. The
test asserts the Available Balance as well as the balance for the same reason.

Rejection and expiry call `release`, which lowers only the Reserved Amount.
Settlement's counterpart on the receiving side is `credit`, which raises only the
balance — nothing is reserved on the destination, so there is no figure it could
overdraw.

Both outward movements share one refusal: **neither may take the Reserved Amount
below zero.** Reaching it means a Transfer settled or released twice, or against a
reservation another Transfer had already consumed. It is an
`IllegalArgumentException` rather than an `InsufficientFundsException`, because it
is a caller's mistake and not a request an operator made — the two exceptions
answer different questions and ticket 21 will map them to different status codes.

---

## Rejection locks both Accounts although only the source moves

`AccountLocking.lockForTransfer` is the whole of what the `accounts` slice
exports, and §30 keeps `AccountRepository` package-private precisely so there is
no second way to reach a balance. Rejection needs the source Account only, and
takes a lock on the destination as well.

That is the right trade rather than an apology for it. The cost is one row lock
held for the rest of a short transaction, against a second entry point into the
slice whose only justification would be saving that lock — and a "lock just this
one Account" method is exactly the kind of surface that, once it exists, gets used
where the ordered pair was required.

---

## The bulk-update hazard, and the fix that would have been worse

Both guarded updates here are `@Modifying` bulk statements, which go straight to
the database and leave Hibernate's persistence context untouched. Two consequences
had to be designed around rather than discovered.

**In `CheckLedger.record`:** an entry loaded earlier in the same transaction would
come back from `findAllByTransferId` still carrying its old, unanswered Verdict —
and a ledger whose last outstanding row still looks outstanding decides `WAIT`,
which is the "`PENDING` for ever" failure again, arrived at from a different
direction. What keeps it safe is an ordering constraint: `record` is the only
reader of those rows and it reads them **after** the update. That is stated in its
Javadoc, because it is a rule the next edit could break silently.

**In `VerdictRecording`:** the locked `Transfer` entity still says `PENDING` after
`advanceFromPending` has moved the row. Nothing reads it again, and `Transfer` has
no mutators, so nothing dirty-checks a stale status back over the row.

**Rejected — `@Modifying(clearAutomatically = true)`**, which is the answer the
documentation suggests and would be a real defect here. Clearing the persistence
context detaches *everything*, including the two Accounts locked in the same
transaction, and their balance changes are held as dirty state on those attached
instances. Clearing them silently drops the money movement while leaving the
Transfer `SETTLED`.

---

## Every claim is falsifiable, and each was falsified

| mutation to production code | test that turns red |
|---|---|
| delete `@Lock(PESSIMISTIC_WRITE)` from `findAndLockById` | `locksTheTransferRowFirst`, `locksTheTransferBeforeTheAccountsItSettlesAgainst` |
| lock the Accounts before the Transfer in `recordVerdict` | `locksTheTransferRowFirst` |
| `Account.settle` lowers the balance without consuming the reservation | 5 in `AccountSettlesAndReleasesWhatItReservedTest`, 2 in `RecordedVerdictsAdvanceTheTransferTest` |
| delete `locked.source().release(...)` from the rejecting path | `oneRejectionRejectsTheTransferImmediately` |
| drop `AND entry.verdict IS NULL` from `CheckLedgerRepository.answer` | `aContradictingSecondVerdictDoesNotOverwriteTheFirst` |
| delete the terminal-status refusal from `recordVerdict` | `aVerdictOnAnAlreadyTerminalTransferIsRefused`, `theSameVerdictTwiceAdvancesTheTransferOnce` |
| delete the `CheckNotRequiredException` guard from `CheckLedger.record` | `aVerdictForACheckTheTransferDoesNotRequireIsRefused`, `refusesAVerdictForACheckTheTransferDoesNotRequire` |
| give `VerdictRecording` a `TransferRepository` field | `onlyTheReservationWritesATransfer` |
| **drop `AND transfer.status = PENDING` from `advanceFromPending`** | **nothing — all 214 tests stay green** |

Each was applied, run, and reverted.

The last row is the point of the first section, stated as evidence rather than as
argument. The guard the ticket names as the mechanism is the one mutation the
suite cannot see, because under the row lock above it there is no state in which
it fires. Writing it into the table as "no test turns red" is more useful than
leaving it out.

### What the concurrency test does and does not establish

`ConcurrentVerdictsSettleTheTransferOnceTest` runs the last two Checks approving
at once, held at their first row lock by `RowLockBarrier`, and asserts that
exactly one thread settles and the money moved once. It shows the design survives
real overlap.

**It is not the falsification of the lock**, and the Javadoc on it says so.
Deleting `@Lock` leaves no `select … for update` on `transfers` for the barrier to
hold the threads at — and on the waiting path there is no Account lock either, so
a thread can run its whole transaction without ever reaching the barrier. The
threads then overlap by luck rather than by construction, which is trap two in
ticket 13's list and the reason that ticket added the barrier in the first place.

What *is* deterministically falsifiable is
`RecordingAVerdictLocksTheTransferTest`, which reads the statements out of
Hibernate through `CapturingStatementInspector` and asserts that the first
`for update` a Verdict issues is against `transfers`. It turns red on removing
`@Lock` and it turns red on reordering the two locks, which is the second claim
the deadlock argument rests on. The two tests are halves of one argument and
neither is worth much alone.

It asserts that on **both** paths, and the second one is why. A Verdict that
leaves the Transfer waiting issues the Transfer's lock and no other, so "first"
is a claim about a list with one entry in it and the ordering is true of a
scenario that never reaches an Account. Settling is where both kinds of lock are
taken and therefore the only place the order can actually be violated:
`locksTheTransferBeforeTheAccountsItSettlesAgainst` asserts three row locks,
`transfers` before both `accounts`. Which of the two Accounts comes first is not
asserted here — the statements carry `?` rather than the identifiers, so
ascending order stays `AccountLockIsASelectForUpdateTest`'s claim and this one
sits above it.

---

## `Check`, `Verdict` and `LedgerDecision` are public; `decide` is not

`record`'s signature is made of the first two and returns the third, so a caller
that cannot name a Check cannot report one. Ticket 19's package-info promised
these went public in ticket 22, where the ledger first reaches the wire; needing
them one ticket earlier is a vocabulary question rather than a second way into the
package, and the package-info now says which.

`LedgerDecision.decide` stays package-private. It is specified over the ledger
rows and the rows do not leave, so exporting the function would export the ability
to decide a Transfer's fate from rows the caller assembled itself.

---

## What this ticket deliberately did not build

- **The endpoint.** Ticket 21. `recordVerdict` raises `UnknownTransferException`,
  `TransferNotPendingException` and `CheckNotRequiredException` and chooses no
  status code for any of them; each carries the fields an answer needs.
- **Expiry.** Ticket 23. The terminal-status refusal is written so that `EXPIRED`
  needs no new branch when it arrives.
- **Cross-currency settlement.** `Transfer` already carries two amounts and
  settlement debits the source's and credits the destination's, so the movement is
  right the day ticket 26 stops copying one into the other. The gap is
  `FundsReservation`'s and is already named in `docs/deferred.md`.
- **An event on settlement.** §17's stream is the outbox's, and there is no
  outbox yet.
