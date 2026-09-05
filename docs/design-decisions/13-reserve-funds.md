# Ticket 13 — reserving funds under concurrency

§6's ordering claim and §13's two figures, finally exercised together: lock,
*then* check the Available Balance, then write the reservation and the `PENDING`
transfer. The rule was settled before the first ticket and
[ticket 12](12-ordered-account-locking.md) built the lock. What was left was the
operation that holds it, and — the part that turned out to be the whole of the
work — **what a test has to do before two threads prove anything.**

Touches §6 and §13 of the [initial decisions](00-initial-decisions.md).

---

## The overdraft check lives on `Account`, not in the service

`Account.reserve(Money)` raises the Reserved Amount and throws
`InsufficientFundsException` itself. `FundsReservation` never compares two
figures.

**Rejected — the check in the service.** A service that reads
`getAvailableBalance()`, decides, and then writes has two statements a future
edit can separate, and the check is then only as good as every caller remembering
to perform it. With the refusal inside the entity there is no reach into the
balance that skips it: reserving *is* the check.

The uncomfortable half is stated on `reserve` itself rather than here — the
entity owns the arithmetic and the *caller* owes the lock. An entity method that
looks like it enforces an invariant, and enforces it only under a precondition it
cannot see, is exactly the thing a reader trusts too far, so the warning belongs
where the reader is.

**Nothing saves the Account**, and §30 is why there was no alternative to weigh:
`AccountRepository` is package-private and locking is the whole of what
`accounts` exports, so the only write available is mutating an entity the
transaction already manages. Ticket 12's tight slice boundary is what leaves the
write here with exactly one shape.

---

## `ReservationRequest` carries a bare count of Minor Units

[CONTEXT.md](../../CONTEXT.md) says a number without a Currency is not Money, and
`Money`'s own Javadoc says there is no bare amount in this context. The request
record contradicts both: `long amountMinorUnits`.

It is the one place where the contradiction is the honest shape. A Transfer's
Currency is the source Account's. The source Account may only be read under the
row lock §6 requires. So a caller could only name a Currency in the request by
reading the Account *first, outside the lock* — which is precisely the read §6
exists to forbid, dressed up as a type-safety improvement.

The count becomes `Money` on the first line after the lock is taken, denominated
by the source Account. The rule "no bare amounts" survives with its scope
narrowed to where it can be true: **inside the transaction**, not on the way in.

**Rejected — a `Currency` on the request, validated under the lock.** It reads
better and it moves the failure later: the caller now has a field it can fill in
wrongly, and the only place to catch that is a comparison under the lock, which
is a second refusal to design, document and answer with a status code. A field
nobody can fill in correctly is worse than no field.

---

## The finding: a start latch proves nothing, and this was measured

The ticket warned about two traps — a transactional test method, and threads that
never overlap — and both warnings were taken. The tests are non-transactional
with manual cleanup, and a `CountDownLatch` released both threads together.

**That was still not enough, and the way it was found is worth recording.**
After the tests were green, the ascending-ID sort was deleted from
`AccountLocking.inLockOrder` — the exact defect §6 exists to prevent — and
`opposingTransfersBetweenOnePairOfAccountsBothComplete` **stayed green.**

The reason is timing, not logic. A latch at the start of an operation lines up
the *beginnings*. The whole of a reservation against an in-memory H2 — two
locks, an update, an insert, a commit — runs in well under the time it takes to
schedule the second thread, so thread one routinely committed before thread two
acquired anything. Both threads ran; neither contended. The test exercised the
code twice rather than concurrently, and a deadlockable design passed it.

### `RowLockBarrier`

Interleaving only becomes decidable at the lock acquisitions themselves, so the
barrier had to move there. `RowLockBarrier` is a test-scoped Hibernate
`StatementInspector` — Hibernate hands it every statement on its way to the
driver — which holds each thread at its **first** `select … for update` until an
expected number of threads have reached one, then releases them together.

A thread already past the barrier runs unimpeded, and that is the whole trick:
both transactions are holding one lock at the moment either reaches for its
second. Under role-ordered locking that is a cycle by construction. Under
ascending-ID locking both threads asked for the same Account first, so one of
them is waiting on the database holding nothing.

**It costs nothing in production.** Nothing in `src/main` knows it exists; it is
registered by `@Import(RowLockBarrier.class)` on the one test class that needs it
and is inert until armed. That distinction is the whole of why the hook is
allowed: [ticket 09](09-create-account-endpoint.md) refuses "production surface
that exists only for a test", and a Hibernate SPI registered from the test
classpath is not production surface. A `reserve` overload that took an injected
barrier would have been.

Ticket 12's "deliberately did not build" list had in fact predicted the shape:
*"a latch to hold each thread after its first acquisition."*

The cost that is real: `@Import` on the test class gives it an application
context of its own rather than the one `BootedApplicationTest` shares, so the
suite pays one extra boot. Worth it for a claim that can fail.

### Every claim is falsifiable, and each was falsified

| mutation to production code | test that turns red |
|---|---|
| delete `.sorted()` from `AccountLocking.inLockOrder` (lock by role) | `opposingTransfersBetweenOnePairOfAccountsBothComplete` |
| remove `@Lock(PESSIMISTIC_WRITE)` from `findAndLockById` | `exactlyOneOfTwoConcurrentTransfersOutOfOneAccountSucceeds` |
| read an Account before taking the locks | `readsNoAccountOutsideTheLock` |

Each was applied, run, and reverted. No test was green under its mutation.
**That table, not the green tick, is the reason to believe the design.**

The third row is `TheBalanceCheckHappensUnderTheLockTest`, which is the
checkbox "the Available Balance check happens after the locks are held"
asserted directly rather than inferred from how a race turned out. The check
issues no SQL of its own — it compares two fields of an Account already in
memory — so what the statement log can see is the *read that put it there*. If
every statement reading an Account during a reservation ends in `for update`,
the figures the check tests against did not come from an unlocked row.

The defect it is aimed at is a plausible one: a validation added ahead of the
lock, to answer `422` early, reads correctly and passes every other test in this
ticket, including the concurrent ones — the real check still runs under the lock
afterwards. Nothing else here would have noticed.

It reuses ticket 12's statement recorder, which is now
`testsupport/CapturingStatementInspector` rather than a class nested inside
`AccountLockIsASelectForUpdateTest`. Hibernate accepts exactly one
`StatementInspector`, so a test class can have the recorder or `RowLockBarrier`
and not both — which costs nothing, since one asks what was issued and the other
changes when.

The single-threaded refusals in `ReservedFundsReachTheTableTest` needed no
apparatus at all: they assert what one reservation writes and — the more easily
lost half — that a refused one writes *nothing*, reading the tables back rather
than trusting the entities that wrote them.

### One test was deleted for failing this standard

An earlier `neverReservesMoreThanTheAccountHolds` asserted the other half of the
checkbox — that the Account never ends up owing more than it holds — and its
Javadoc claimed the table's `ACCOUNTS_RESERVED_WITHIN_BALANCE` constraint would
have caught a violation. Both were wrong, and measurably: under the no-lock
mutation above it **stayed green** while its sibling failed.

The reason is worth keeping, because the intuition it corrects is a natural one.
Two unlocked transactions racing on the same reservation do not *over*-reserve.
They lose an update: both read a Reserved Amount of zero, both write 150.00, and
the column lands on exactly the 150.00 a correct run would produce. The
constraint is never approached, the balance is never negative, and the damage —
two `PENDING` Transfers backed by one reservation — is only visible in the
*count of outcomes*, which is what the surviving test asserts. Its balance
assertions were folded in there; the test that could not fail is gone.

### What the barrier still cannot prove

That the two lock acquisitions interleaved *inside the database* — and, more
narrowly than the first draft of this file claimed, not even that both statements
were issued before either returned. `inspect` runs before execution, so releasing
two threads together does not stop one of them from completing its whole
transaction while the other is still between the barrier and its first row.

Waiting at the *second* lock instead would be the stronger guarantee, and it is
rejected because it is broken: under the correct ascending-ID design the losing
thread is blocked in the driver on its first row and never reaches a second
statement to be counted, so the barrier would hang the passing case.

What the barrier does remove is the *systematic* failure — a thread that had not
started when the other committed, which is what made the start-latch version
green against a broken design. Beyond that the falsification table stands in for
a guarantee, and it is repeatable rather than lucky: the ascending-ID mutation
was run five times and deadlocked five times.

`docs/deferred.md` still names running this suite against Postgres as a
production prerequisite. H2 times a lock wait out after about a second where
Postgres waits indefinitely, and these are now the tests that would notice.

---

## Cross-currency transfers: a hole, not a refusal

Both amounts on the Transfer are denominated by the source Account, so a
Transfer between two differently-denominated Accounts is written with the wrong
Currency in the credited column rather than refused.

A `CrossCurrencyTransferNotSupportedException` was built and then **deliberately
removed.** The argument for keeping it was that the row it prevents settles into
the wrong money silently. The argument that won: the correct answer to a
cross-currency Transfer is not a refusal but a conversion, §15 already says where
the rate comes from, and ticket 26 is where the credited amount stops being a
copy of the debited one. A refusal added here is a rule ticket 26 has to
*reinterpret* rather than delete, and ticket 13's brief is the concurrency claim.

The hole is named in [deferred.md](../deferred.md) with its residual risk stated
rather than left to be rediscovered: until ticket 26, the source Account's own
books stay right and the destination side is wrong.

---

## `LOCK_HOLDERS` gains a name, not a class literal

Ticket 12 left `LockedPathTouchesOnlyTheDatabaseTest.LOCK_HOLDERS` as a
hand-written list with a Javadoc explaining why it must not become a query over
callers, and predicted ticket 13 would add one entry. It does — but as a
fully-qualified **string**, because `FundsReservation` is package-private in
`transfers` and cannot be named from `accounts`.

Widening its visibility so a test could hold a `Class` literal would be spending
the §30 boundary to buy a compile-time reference, which is the wrong direction.
ArchUnit's `JavaClasses.get(String)` throws on a name that matches nothing, so a
typo or a rename fails loudly rather than quietly asserting over an empty set.

The entry has an expiry: ticket 26 gives `FundsReservation` an Exchange Rate port
to call in the phase *before* the transaction, which this reachability rule
cannot distinguish from a call inside it. When that lands the entry comes out and
ticket 26's own phase-ordering assertion replaces it. That is written into the
Javadoc, because the alternative is a future ticket weakening the rule to make a
correct design pass.

---

## What this ticket deliberately did not build

- **The endpoint.** `POST /api/transfers`, the `201`, and the status and
  `ProblemType` URN for `InsufficientFundsException` are ticket 14's. Neither
  exception added here carries a `ProblemType`, for ticket 12's reason: a URN
  chosen without the endpoint that returns it is chosen blind.
- **Idempotency.** §4 puts the key's flip to `SUCCEEDED` in the same commit as
  the reservation, which widens this transaction. Ticket 16 owns it, and the
  `@Transactional` here is where it goes.
- **Any transition off `PENDING`.** Settlement (20) and expiry (23) each lower
  the Reserved Amount, and each is a conditional update guarded on the status it
  leaves. `Account` has `reserve` and nothing else for the same reason `Transfer`
  has no status setter — a method written for a caller that does not exist is a
  guess at its signature.
- **A `Money`-typed request.** See above; it is not a simplification postponed,
  it is a shape rejected.
