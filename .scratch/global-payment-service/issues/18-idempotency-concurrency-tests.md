# 18: Proving the guarantee under the race it exists for

**What to build:** The tests that demonstrate idempotency actually holds when two requests
arrive at once, rather than when they arrive one after another. Everything else about the
mechanism has been asserted sequentially; this is the assertion that matters.

Two scenarios, both against the running application with a real database:

- **Two concurrent requests carrying the same key and payload** produce **exactly one**
  `201` and **exactly one** `409` — and exactly one Transfer exists afterwards.
- **Two concurrent retries of a `FAILED` key** result in **exactly one** execution, so the
  recovery path is not itself a double-charge.

**The three traps that make these tests pass while proving nothing:**

- A transactional test method runs everything in one transaction, so the second thread
  cannot see the first thread's committed claim, both "succeed", and the test is worthless.
  Write these non-transactional with manual cleanup.
- Without a latch, thread one finishes before thread two starts and the race never occurs.
  Line them up on a virtual-thread executor and release them together.
- Assert **exactly** one of each outcome, not "at least one" — the weaker assertion passes
  in the failure case this test exists to catch.

Also worth guarding: context caching is per-configuration, so gratuitous variation in
profiles, property overrides or mocked beans turns one context boot into many.

**Blocked by:** 17

**Status:** done

- [x] Concurrent same-key requests yield exactly one `201` and one `409`, with one Transfer
      persisted
- [x] Concurrent retries of a failed key yield exactly one execution
- [x] The tests are non-transactional and clean up after themselves
- [x] A latch forces genuine overlap; removing it is demonstrably the only reason the test
      would still pass
- [x] The suite does not boot a second application context for these tests

---

## Comments

### Built, 2026-09-06

Four files, one of them new, and **nothing in `src/main` changed**. The design record is
[`docs/design-decisions/18-idempotency-concurrency-tests.md`](../../../docs/design-decisions/18-idempotency-concurrency-tests.md);
this is the short version.

```
src/test/java/hu/bankmonitor/payments/transfers/ConcurrentRequestsUnderOneKeyExecuteOnceTest.java (new)
src/test/java/hu/bankmonitor/testsupport/RowLockBarrier.java
src/test/java/hu/bankmonitor/payments/transfers/TransferScenario.java
src/test/java/hu/bankmonitor/payments/transfers/RetryingATransferRequestMovesMoneyOnceTest.java
```

`./mvnw -o test` — 269 tests, 0 failures, of which two are this ticket's. That no production
code moved is the result rather than the effort saved: the two branches ticket 17 left
unreachable — `RequestInProgressException` raised by a claim that is genuinely held, and the
loser of `reclaimFailed` — are now reached by a real race, without a seam built to reach
them.

The falsification table is in the design record and in the test's own Javadoc. Its top half
is the point: with the unique constraint dropped from `V3`, both tests go red 20 runs out of
20; with `reclaimFailed`'s guard on `FAILED` dropped, the retries test does.

### Three scope calls, argued in the design record and none of them confirmed

**The fourth checkbox is ticked for its second half, not its first.** There is no start
latch. What forces the overlap is `RowLockBarrier`, armed at the statement the two contenders
meet at, and the latch this ticket asked for was measured to change no outcome in forty runs
on top of it — while [ticket 13](../../../docs/design-decisions/13-reserve-funds.md) had already measured a start latch
*insufficient* on its own. The "removing it is demonstrably the only reason the test would
still pass" half is met and measured: with both armings removed the first test goes red 3/20
for lack of overlap, and the second silently stops reaching the branch it is about
(20/20 → 8/20 → 0/20 as the rendezvous moves). Design decisions §25's third trap was
corrected in place as a result.

**The two races are lined up in two different places**, which the ticket's single "release
them together" does not anticipate. Two first attempts never both reach a row — the loser is
turned away by the constraint without touching an Account — so the winner is held at its
first row lock until the test has the loser's answer in hand. Two retries meet at the *read*
of the claim instead.

**`RowLockBarrier` grew a second arming rather than gaining a sibling class**, because
`@Import` participates in the Spring context cache key and a second barrier class would have
cost a third application context — the fifth checkbox. The price is a test-support class with
three arming methods and a name now narrower than what it does.

### Review, 2026-09-06

`/mattpocock-skills:code-review` against `2ad2d80`, both axes. All five checkboxes verified
against the code rather than against the write-up — including the last one, which is the only
one nothing asserts: all three concurrency classes carry `@Import(RowLockBarrier.class)` and
no mock or property override, so they are one cached configuration between them. Four things
changed as a result.

**The one that could have hollowed the test out silently.**
`holdEachThreadAtItsFirstStatementNaming` documented case-insensitive matching and did not
have it: `holdsBackA` lowercased the statement and not the fragment, so a fragment armed in
any other case would have matched nothing. A barrier that holds no thread does not fail — it
leaves the race unlined-up, which is the failure mode with no symptom this ticket is about.
Lowercased once at arming.

**The test's poll and the barrier's wait were both ten seconds.** A genuinely stuck thread
made the report a coin toss between naming the defect and saying only that no answer arrived.
The poll is thirty now, so the barrier always gives up first and the assertion that was going
to fail is the one that does.

**The application-context count was off by one, in the design record and in the barrier's
Javadoc.** Three classes already import `CapturingStatementInspector`, so the barrier's
context is not the suite's second and a sibling barrier would not have been its third. The
argument is unchanged and the number is gone: what the suite boots is a count of
configurations, not of test classes.

**The retries test asserted the refusal's type URN and not its `Retry-After`**, where the
first race asserts both. The two `409`s mean opposite things, and half the distinction was
being checked in only one of the two places it is made.

**Three findings noted and not acted on.** The falsification table appears in the test's
Javadoc, in the design record and in summary here — the shape ticket 13 already has, and what
`AGENTS.md` asks for: the full statement in the durable Javadoc, the reasoning and rejected
alternatives in the record. `RowLockBarrier` now has three arming methods, two of them
one-line delegates with a single caller each; that cost is written into the record rather than
paid off, because collapsing them would put the choice of rendezvous back into every call
site. And checkbox 4's wording is stronger than its measurement, which the section above
already says in more detail than the checkbox can.
