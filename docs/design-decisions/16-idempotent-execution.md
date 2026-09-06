# Ticket 16 — the idempotency record and its claim mechanics

The third table, and the first slice whose subject is not a shape but a set of
transaction boundaries. §4 and §5 already said which writes have to commit
together and which have to commit apart; this is what that turned out to mean in
Spring, what it named the mechanism, and the one sentence in §4 the build found
to be wrong.

Touches §3, §4, §5, §29 and §30 of the [initial decisions](00-initial-decisions.md).

---

## The Idempotency Key cannot be the primary key, and the reason is Spring Data

The obvious table has the key as its identifier. It is unique by definition, it
is the only thing anything looks a row up by, and a surrogate `id` beside it
looks like ceremony.

It is not, and the reason has nothing to do with the schema. `Repository.save`
is not an insert — it is "insert or merge", and Spring Data decides between them
by asking whether the identifier is already set. An entity carrying an assigned
key is never new, so every claim would go through `EntityManager.merge`, which
is:

1. a `SELECT` for the row, then
2. an `INSERT` if it was absent, or **an `UPDATE` if it was there**.

Both halves are wrong here. The `SELECT` is the read-then-write §5 spends two
paragraphs eliminating — the window between "no prior request found" and the
write is exactly the race the mechanism exists to close, and putting it back
inside the claim itself is the most expensive place to put it. The `UPDATE` is
worse than the race: a second caller arriving with the same key would not be
refused at all. It would silently overwrite the live claim's payload hash with
its own and be told it had claimed the key, while the first request was still
using it.

None of that is visible in a test that never runs two callers, because a merge
of a row that is not there produces exactly the insert you wanted.

**So the record carries a surrogate `id` with `GenerationType.IDENTITY`, and the
key carries a named unique constraint instead.** `save` is then an unambiguous
insert, and `IDENTITY` makes Hibernate issue it immediately rather than at flush
— so the constraint violation arrives at the `save` call, which is where the
caller can do something about it.

The named constraint is worth the line it costs:
`IDEMPOTENCY_RECORDS_KEY_IS_UNIQUE` appears verbatim in H2's message, so the
test asserts on the rule that was broken rather than on an error code, and
ticket 17 can tell this violation apart from any other the row might cause.

**Rejected — implementing `Persistable` to override `isNew()`.** It would keep
the natural key and force the insert, at the cost of a transient `@Transient`
flag on the entity and a rule every future reader has to know. The surrogate
identifier is the same fix with nothing to remember, and it matches `accounts`
and `transfers`.

---

## Four transitions, four propagations, and the one §4 implies without naming

Every method on `IdempotencyClaims` is one statement and a postcondition. The
propagation is not decoration around the work — it is the work, and very nearly
the only thing that class contains.

| Transition | Propagation | Because |
|---|---|---|
| `claim` | `REQUIRES_NEW` | a concurrent duplicate cannot hit a constraint against an uncommitted row |
| `reclaimFailed` | `REQUIRES_NEW` | as above, and the row lock must not be held across the work |
| `markSucceeded` | **`MANDATORY`** | it has to commit with the money movement it reports, or neither |
| `markFailed` | `REQUIRES_NEW` | it has to survive the rollback it reports |

Three of those are §4 and §5 read literally. The fourth is a choice those
sections leave open, and it is the one worth recording.

§4 requires the `SUCCEEDED` flip to commit **with** the reservation: "if they
could commit separately, a crash between them leaves funds reserved and the
record stuck at `IN_PROGRESS`". The obvious annotation for "joins the caller's
transaction" is `REQUIRED` — and `REQUIRED` also means "starts one if there
isn't one", which is the single mistake this design cannot survive. Called
outside phase three, it would commit the success on its own, return normally,
and look like it worked. The divergence only surfaces later, on a retry that
replays a `201` for money that never moved.

**`MANDATORY` removes that reading.** It joins an existing transaction and
throws `IllegalTransactionStateException` when there is none, so "somebody
called this outside the money-moving transaction" is an exception at the call
site instead of a corrupted record found weeks later.
`markingAKeySucceededOutsideTheMoneyMovingTransactionIsRefused` pins it.

The conditional update is only on `reclaimFailed`, which is the rule worth
stating once: **the guarded update is for the transitions several callers race
for; the winner's own writes are unguarded.** Reclaiming a `FAILED` key has
contenders by construction (§5's second trap), so it is
`UPDATE … WHERE key = ? AND status = 'FAILED'` and the rows-affected count says
who won. `markSucceeded` and `markFailed` are issued by the caller already
holding the claim, and a key is only held once.

**Unguarded is not unchecked, and this is the correction the review found.**
Both of the winner's writes match on the key alone, so a rows-affected count of
zero there cannot mean a race was lost. It can only mean the key names no row —
a caller marking a key nobody claimed. `IdempotencyClaims` raises
`EmptyResultDataAccessException` rather than returning normally, because the
silent version is this ticket's own failure wearing a disguise: the caller is
told the claim moved, while the claim it meant to move sits at `IN_PROGRESS`
with nothing left in the design that can ever reach it. Nothing else would ever
report it; §5 answers `IN_PROGRESS` with `409` and means it.

So the count is read for a different question than `reclaimFailed`'s — not *did
I win* but *was there anything there* — and neither method returns it, because a
caller has nothing to decide on the answer. `markFailed`'s check carries one
instruction for ticket 17: it throws while an exception is already on its way
up, so it must not be allowed to replace it. Report the original and attach this
one as a suppressed exception.

Which answers the objection this section opened itself up to: a class of one-line
delegations is a Middle Man. It very nearly was, and the honest reason it could
not simply be folded into the repository was never *impossible* —
`@Transactional` works on Spring Data methods too. It was §30: the repository is
package-private and stays that way, and what ticket 17 should be calling is a
`claim` / `reclaimFailed` / `markSucceeded` / `markFailed` vocabulary rather than
four annotated queries. With the check, the objection stops being interesting.
Every method now translates a rows-affected count into what its caller actually
needs — a `boolean` for the one transition that has contenders, an exception for
the two that cannot legitimately match nothing — and that translation has no
business on an interface whose methods are queries.

---

## `REQUIRES_NEW` on `markFailed` has a cost §5 does not mention

§5 says to mark `FAILED` in a new transaction, and gives the reason: a rollback
would otherwise undo the status write. That is right, and the test that pins it
fails as expected the moment the annotation is downgraded to `REQUIRED`.

What the section does not say is that a new transaction **cannot see the locks
the suspended one is holding**. So the obvious place to report the failure — a
`catch` inside the operation that failed — is a trap when that operation has
already written to the row:

```java
transaction {
    claims.markSucceeded(key, body);   // takes the row lock
    ...something throws...
    claims.markFailed(key);            // new transaction; waits on that lock
}
```

Measured on H2: `CannotAcquireLockException`, "Timeout trying to lock table
`IDEMPOTENCY_RECORDS`", **after 2024 ms**. And then the worst part — the row is
left at `IN_PROGRESS`, which is precisely the state `markFailed` exists to
prevent. The symptom is a request that hangs for two seconds and then burns the
key permanently, reported as a lock timeout that names nothing to do with the
mistake.

It is recorded in `markFailed`'s Javadoc as a warning, because it is the case
where the obvious edit is the wrong one, and asserted by
`markingAKeyFailedFromInsideTheTransactionThatWroteToItWaitsOnItself`. The rule
for ticket 17: **`markSucceeded` belongs inside the transaction, `markFailed`
belongs after it.**

---

## §4's surviving crash window is *not* "what the retry path exists for"

§4's last sentence reads:

> Bundled, the only crash window is after phase 1: a claimed key with nothing
> reserved, which is a stale row and exactly what the retry path exists for.

The first half is right and the second half is false, which is why the clause
has been struck from §4 rather than left to mislead.

The retry path is §5's `FAILED` row: reclaim by conditional update, then
execute. A crash between phase 1 and phase 3 does not leave a `FAILED` row. It
leaves an `IN_PROGRESS` one, and §5's own table answers `IN_PROGRESS` with
`409 Conflict` — for that key, forever. Nothing in this design ever moves a row
out of `IN_PROGRESS` except the process that claimed it, and that process is
gone.

The comparison §4 is drawing still holds, and it is still the reason to bundle:
unbundled, the stuck key describes a transfer that **really happened** with
funds reserved behind it; bundled, it describes one that never happened at all.
Nothing is lost and no money is stranded. But the key is spent, and a client
retrying it is told to wait for something that will never finish.

Recovering it needs a claim age and something to sweep on it, which is a column
and a scheduled job that no ticket in the plan has. It is written up in
[deferred.md](../deferred.md) instead, which is also where the decision to ship
this record **without a timestamp** is recorded.

---

## What this ticket deliberately did not build

- **`IdempotentExecution` itself.** The port is quoted in the ticket, and every
  section from §3 down is written in terms of it, which made declaring it here
  tempting. It is ticket 17's, because this repository's rule since
  [ticket 08](08-account-entity.md) is that no surface is declared without a
  call site — `AccountRepository`'s method list, `TransferRepository`'s two
  methods and `Transfer`'s absent transitions are all the same rule — and an
  interface with no implementation and no caller is that rule's clearest
  violation. What this ticket owes 17 is the mechanics underneath it, which is
  what it shipped.
- **A `claimed_at` timestamp**, on [ticket 11](11-transfer-entity.md)'s
  precedent: the status is the single answer to how far a claim has got, nothing
  here reads a clock, and the ticket that first needs a claim's age should add
  the column with a reader to shape it. Its absence is what makes the stranded
  `IN_PROGRESS` row above unrecoverable, so it is a deferral rather than an
  omission and is written down as one.
- **A lookup by key.** `findByIdempotencyKey` is the first thing ticket 17 will
  need — §5 reads the row that beat it — and it has no caller today. Same rule.
- **Catching the constraint violation.** `claim` lets
  `DataIntegrityViolationException` propagate, because the caller has to read the
  losing row anyway to tell "wait and retry" from "you have made a mistake", and
  because catching it inside the transaction that caused it is not something
  Hibernate supports: the session is done, and the commit that follows fails on
  its own.

---

## Two places that rule is bent, and they are not bent the same way

`IdempotencyRecord`'s five getters have no call site either. Nothing in this
slice loads a record: every read is a `SELECT` in a test and every write is an
`UPDATE`. Applied literally, the rule deletes all five and ticket 17 adds back
the three it reads.

They stay, because the rule is about **surface a caller might build on**, and a
package-private accessor on a package-private entity is not that — nothing
outside this package can name the type, let alone call it. What deleting them
buys is an `@Entity` with no way to read any of its fields, which is a stranger
thing to hand the next reader than five getters; what it costs is the field-level
intent they carry, which is where the answer to *why a hash rather than the
payload* currently lives. The rule is worth keeping literal where it governs a
boundary, and not where it governs a data class behind one.

`markSucceeded` is the harder case, and not for the same reason. It is no more
externally reachable than the getters — same package-private class — but it is
*behaviour*, with four tests and a schema column behind it, and its only
described caller is ticket 17's phase three. The alternative was not "add it in
17"; it was shipping `response_body` and the constraint below with nothing in the
codebase that could ever write either. The ticket asks this record to hold the
stored response, so the column is in scope — and a column no code populates,
under a check constraint asserting a rule about it, is the worse thing to
inherit. `validate` does not catch dead schema. So the method ships early and
knowingly, and four of the fifteen tests exist for it.

---

## The table carries an invariant the entity cannot break

```sql
constraint idempotency_records_response_only_when_succeeded
    check ((status = 'SUCCEEDED') = (response_body is not null))
```

Both directions of one rule: a stored response means the work finished, and
finished work has a response to replay. `V1__accounts.sql`'s constraints are
there on the same reasoning — the invariant is enforced in the service, and what
the constraint adds is that a path *around* the service fails the write.

This one earned its keep during the mutation checks below. With the `FAILED`
guard removed from `reclaimFailed`, an unrelated test failed by trying to drag a
`SUCCEEDED` row with a stored response back to `IN_PROGRESS` — an illegal
transition the constraint caught even though no assertion was looking for it.

It also forecloses something, which is the reason to say so here rather than
leave ticket 17 to discover it. **A `FAILED` row can never keep a response.** If
17 decides a terminal failure's body should be stored and replayed — the
outcome-storing model *Terminal vs retryable transfer failures* in
[deferred.md](../deferred.md) contemplates — this constraint is in its way, and
`markFailed` against a `SUCCEEDED` row is a constraint violation rather than a
no-op for the same reason.

It stays anyway, on the rule this file has already leaned on twice: the
biconditional is true of the design as it stands, and weakening a true invariant
for a caller that does not exist is the same mistake as declaring a port for one.
Relaxing it is one `alter table` in a `V4` migration if 17 needs it, which is
what §29's one-migration-per-slice decision already anticipates. Ticket 17's file
carries the note.

`= any (array[…])` for the status check, never `in (…)`: ticket 11 has why, and
the README beside the migrations has the short version.

---

## The test is deliberately not transactional, and was checked by breaking things

`@DataJpaTest` wraps each method in a transaction and rolls it back, which here
would hide every distinction the test exists to draw. A claim nobody else can
see has serialised nothing; a failure marked in the transaction that failed is
not marked at all. §25 names this trap for the concurrency tests of ticket 18,
and it applies one layer down as well — so the class is
`@Transactional(propagation = NOT_SUPPORTED)` with an `@AfterEach` that clears
the table by hand.

That the tests actually bite was checked by mutation rather than assumed, since
a test of a transaction boundary is exactly the kind that passes while proving
nothing:

| Mutation | What failed |
|---|---|
| `markFailed` → `REQUIRED` | `markingAKeyFailedSurvivesTheRollbackOfTheOperationItReports` |
| `claim` → `REQUIRED` | `aClaimAndAReclaimBothOutliveACallersTransactionThatRollsBack` |
| `reclaimFailed` loses `AND status = 'FAILED'` | both reclaim tests, plus the constraint above |
| the rows-affected check never fires | `markingAKeyNobodyClaimedIsRefused`, `markingTheWrongKeyLeavesTheRealClaimAlone` |

The middle row is why that test exists at all. With no caller's transaction
open, `REQUIRED` and `REQUIRES_NEW` commit at the same moment and are
indistinguishable — which is the shape of every other claim assertion in the
file. Only a caller that rolls back tells them apart.

`@Import(IdempotencyClaims.class)` is needed because `@DataJpaTest` does not
scan `@Service` beans, and it makes this class its own cached context. That is
the cost §25 warns about, paid once and deliberately: the alternative was two
test classes with different configurations, which is two contexts for the same
work.
