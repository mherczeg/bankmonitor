# Ticket 18 — proving the guarantee under the race it exists for

Everything about idempotency had been asserted sequentially: ticket 16 against the
table, ticket 17 through the port and over the wire, each time with the second
request arriving after the first had finished. All of it would stay green against
a design that read the table before writing it. This ticket is where such a design
fails.

Nothing in `src/main` changed. The whole of the output is two tests, one
generalised test-support class, and a fixture that now empties a fourth table —
so what there is to record is not what was built but **what the tests are allowed
to believe**, and the measurements that decide it.

Touches §3, §4, §5, §6 and §25 of the [initial decisions](00-initial-decisions.md).

---

## The ticket's start latch was measured redundant, and dropped

Ticket 18 asks for the threads to be lined up "on a virtual-thread executor and
released together", and §25's third trap says the same thing in more detail. The
executor is there. **The latch is not**, and this is a deliberate departure rather
than an oversight.

[Ticket 13](13-reserve-funds.md) had already measured a start latch *insufficient*:
with two threads released together at the start of a reservation, deleting the
ascending-ID sort from `AccountLocking.inLockOrder` — the exact defect §6 exists to
prevent — left the deadlock test green. A latch lines up two *beginnings*, and
against an in-memory database a whole transaction finishes in less time than it
takes to schedule the second thread.

What this ticket adds is the other half: with `RowLockBarrier` armed, a start latch
is also *redundant*. Added on top of the barrier armings, it changed no outcome in
forty runs — twenty repeats of each of the two tests, every one of them landing
exactly where the same run without it landed.

**Rejected — keeping it anyway, as belt and braces.** Apparatus that neither adds
nor removes a failure is not free. It is the thing the next reader credits for the
overlap, so the test's account of itself becomes false while every assertion in it
stays true; and it is one more moving part between a red run and its cause. The
falsification table below is what makes the barrier believable, and nothing in it
improves with a latch in front.

`ConcurrentReservationsHoldTheBalanceTest` reached the same conclusion in ticket 13
and `ConcurrentVerdictsSettleTheTransferOnceTest` inherited it in ticket 20; neither
has a start latch. All three concurrency classes now have one shape: arm the barrier
at the statement the contenders must meet at, then send the requests.

> This corrects §25's third trap in place, which had prescribed the latch as what
> makes the race happen.

---

## Two races, and they meet in different places

The two scenarios look symmetrical — same key, two requests, exactly one execution
— and they are not. Where the contenders actually meet is different in each, so
each needs its own rendezvous point, and `RowLockBarrier` grew a second arming to
supply it.

### Two first attempts: only one thread ever reaches a row

The unique constraint on `idempotency_records` separates them (§4's phase one).
The winner claims the key and goes on to lock two Accounts; the loser's insert is
refused, it reads the row, finds `IN_PROGRESS` and is answered — **without ever
touching an Account.**

So there is no second arrival to count. A barrier that waited for two threads at a
row lock would hold the winner until its ten-second timeout, because the loser was
never going to arrive. `holdTheOneThreadThatReachesARowLock` instead holds the one
thread that does, and the *test* supplies the release: it collects the first answer
to come back — necessarily the loser's, since the winner is held inside its
transaction — and only then lets the winner go.

That inversion is the whole point of the test. What it guarantees is that the
loser was refused **while the claim was still open**, rather than after it had
closed and become a replayable `SUCCEEDED`. Both are correct behaviour and only
one of them is the branch under test.

### Two retries of a failed key: both reach a row, and the decision is upstream

Both retries get past the constraint identically, and the thing that decides
between them is §5's guarded update —
`UPDATE … SET status = 'IN_PROGRESS' WHERE key = ? AND status = 'FAILED'` — whose
losing branch is what [ticket 17 left to this one](../../.scratch/global-payment-service/issues/17-duplicate-resolution.md).

Lined up at the row lock, that branch was mostly not reached at all: the loser
arrived after the winner had committed, read a claim that was already taken, and
was turned away by a branch *above* the one under test. Answering the same `409`,
for a different reason. **Measured:** with `reclaimFailed`'s guard on `FAILED`
deleted, the test goes red 20/20 lined up at the read of the claim, 8/20 lined up
at the row lock, and 0/20 with nothing lining the threads up at all.

So the barrier is armed on the read instead — `from idempotency_records`. Held
there, neither retry has reached the update, so both see the `FAILED` they contend
over and both go on to contend.

**Rejected — arming on the insert that fails immediately before that read.** It is
one statement earlier and would line the threads up just as well today, and it
names the wrong thing: an insert that is *about to violate a constraint* is a
rendezvous point that exists only because duplicates are currently serialised by a
constraint. §3 names "pessimistic `SELECT … FOR UPDATE` instead of relying on
constraint violation" as a replacement the seam holds a place for. The day that
swap happens the insert stops being a rendezvous and the barrier lines up nothing
— silently, because a barrier that holds no one still passes. The read of the row
survives the swap.

### The arming takes a statement fragment, and a table name is the honest one

`holdEachThreadAtItsFirstStatementNaming(fragment, threads)` matches
case-insensitively against the SQL on its way to the driver. A test can name the
table it means; it cannot name how Hibernate happens to spell the statement around
it, and a fragment that encoded the spelling would be a test coupled to a mapping
detail that no assertion depends on.

`for update` — the original arming, now `holdEachThreadAtItsFirstRowLock` — is the
exception that proves it, because there the lock *is* the thing being named.

---

## A second arming on `RowLockBarrier`, not a sibling class

The ticket's last checkbox is "the suite does not boot a second application
context for these tests", and it is satisfied structurally rather than by a test
that names it.

`@Import` participates in Spring's context cache key, so a test class that
imported a new `ClaimReadBarrier` would be a distinct configuration and would boot
**one more** application context. Ticket 13 paid for the barrier's own and recorded
it as the price of a concurrency claim that can fail; another for the same mechanism
differently packaged is not that. All three concurrency test classes import
`RowLockBarrier` and nothing else, so all three share one boot between them — which
is also what the three classes importing `CapturingStatementInspector` do with
theirs, and the reason the suite's context count is a count of *configurations*
rather than of test classes.

Two further reasons the sibling would have been worse:

- Hibernate accepts exactly **one** `StatementInspector` — ticket 13's finding
  about `CapturingStatementInspector`. Two barrier classes would not have composed
  in a single context even if the cache had been free.
- The two armings share the state that makes either of them correct: the
  `ThreadLocal` recording *which arming* a thread has already been held for, so a
  pooled thread is not waved through the next race. Split across two classes that
  is duplicated, and a copy that drifts degrades a concurrency test to no
  synchronisation without failing.

**The cost, stated rather than left to be noticed:** `RowLockBarrier` now exports
three arming methods, two of which have exactly one caller each, and its name is
narrower than what it does — it holds threads at statements, of which row locks are
one kind. Renaming it would touch three test classes and buy nothing a reader of
its Javadoc does not already get.

---

## `TransferScenario` empties `idempotency_records`

Ticket 17 left this: `RetryingATransferRequestMovesMoneyOnceTest` cleaned the table
by hand and "whatever 18 builds for concurrent fixtures should absorb that". The
delete moved to the base class and both private copies are gone.

**In the base class rather than in the two subclasses that make claims**, because a
leftover key is not a hazard those two subclasses have and the others do not — it
is a hazard of the endpoint, which every `TransferScenario` subclass may call. A
list of the classes that currently make claims is a list that stops being true
without failing.

The failure it prevents is worth naming because it reads as a bug in the code under
test rather than in the fixture. Phase one commits the claim **alone and
immediately** (§4), which is exactly what makes it survive a test method that rolls
nothing back — there is no transaction to undo it. The next test to use the same
key is then answered by the previous test's claim: a `409` where the test expected
a `201`, or a replayed `201` naming a Transfer that no longer exists.

The claims are deleted **last and in any order**, unlike the other three tables,
which go ledger → transfers → accounts because each points at the one below. No
foreign key reaches an Idempotency Key at all, which is §3's shape showing through
the schema: the claim sits *above* the work rather than beside it, so that
replacing the mechanism does not migrate the money.

---

## Every claim is falsifiable, and each was falsified

Twenty repeats of each test per row, against `2ad2d80`, by substituting
`@RepeatedTest(20)` for `@Test`.

| Injected | First attempts | Retries |
|---|---|---|
| the unique constraint dropped from `V3__idempotency_records.sql` | 20/20 | 20/20 |
| `AND claim.status = … FAILED` dropped from `IdempotencyRecordRepository.reclaimFailed` | — | 20/20 |
| *neither race lined up* — both `rowLocks.hold…` calls removed | **3/20 red** | 0/20 |
| *a start latch added instead of the barrier armings* | 0/20 | 0/20 |

**The top two rows are defects in production code, and both tests kill the first
one twenty times out of twenty.** Without the constraint, two first attempts both
claim the key and both reserve; two retries do the same. That is the assertion the
whole mechanism rests on and it had no test until now.

**The bottom two rows are the apparatus failing, not the code.** They are why the
barrier is here, and they are the reason this file exists at all:

- Unlined-up, the first test reports
  `Expecting [201, 201] to contain exactly in any order [201, 409]` in three runs
  out of twenty. Seventeen times the overlap simply did not happen: the second
  request arrived after the first had finished and was correctly replayed its
  stored `201`. A test that is right about a correct system 17 times out of 20 for
  the wrong reason is worse than no test, because the three red runs read as
  flakiness to be retried away.
- Unlined-up, the second test stays green in all twenty — while quietly ceasing to
  reach the branch it is about, as the 20/8/0 figures above show. That is the
  failure mode with no symptom.

Which of the two requests wins is the database's decision and neither test has one:
the answers are collected as a pair and asserted with
`containsExactlyInAnyOrder`.

Each assertion is a **count**, never an "at least". A design with no mutual
exclusion reserves twice and writes two Transfers, and every `hasSizeGreaterThan`
or `anySatisfy` written against it passes. The three counts in the first test rule
out three different failures: two `201`s means the requests did not overlap or both
were served from one claim; two Transfer rows means the operation ran twice; a
doubled Reserved Amount means it ran twice and left a Transfer somewhere the row
count does not look.

The second test funds the source Account **after** the failure and by enough for
two Transfers. Funded for one, a double execution would be refused by the overdraft
check instead, and the test would be asserting that an Account has a balance rather
than that a key was claimed once.

---

## What this ticket deliberately did not build

- **No production seam for the tests, and none was needed.** Two branches ticket 17
  left unreachable — `RequestInProgressException` from a genuinely held claim, and
  the loser of `reclaimFailed` — are now reached without a line of `src/main`
  changing. That is the finding, not an accident: the alternative on the table was
  mocking `IdempotencyClaims` to return `false`, which asserts that an `if` was
  typed correctly and nothing about a race. Ticket 09's rule against production
  surface that exists only for a test is not bent here for the same reason ticket 13
  did not bend it — a Hibernate SPI registered from the test classpath is not
  production surface.
- **Not a stress test.** Two threads, not fifty; no loop counting outcomes over N
  attempts. Fifty threads under one key would exercise the same two branches and
  trade an exact assertion for a distribution, and a test whose pass condition is a
  probability cannot be read as a claim.
- **No third scenario for the payload-mismatch `409` under concurrency.** It is
  decided by the same read of the same row as the in-progress `409` and takes a
  different branch of the same comparison; the race that would put it there is the
  race already run.
- **Not run against Postgres.** `deferred.md`'s *Verifying the locking design
  against Postgres* entry stands, and these two tests add little to it: what they
  contend over is a unique constraint and a guarded update, and neither turns on
  how long an engine waits for a row lock. The barrier holds the winner *before*
  its first lock statement rather than while it holds the lock — `inspect` runs
  ahead of execution — so the open transaction that spans the duplicate's round
  trip is holding a connection, not a row.
