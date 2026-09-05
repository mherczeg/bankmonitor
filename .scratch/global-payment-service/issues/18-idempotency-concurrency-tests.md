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

**Status:** ready-for-agent

- [ ] Concurrent same-key requests yield exactly one `201` and one `409`, with one Transfer
      persisted
- [ ] Concurrent retries of a failed key yield exactly one execution
- [ ] The tests are non-transactional and clean up after themselves
- [ ] A latch forces genuine overlap; removing it is demonstrably the only reason the test
      would still pass
- [ ] The suite does not boot a second application context for these tests
