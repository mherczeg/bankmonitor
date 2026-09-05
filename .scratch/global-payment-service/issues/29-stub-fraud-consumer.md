# 29: A stub Fraud Detection consumer, so the lifecycle is visible

**What to build:** A profile-gated stand-in Fraud Detection service that reacts to a
Check-requested event by reporting a Verdict a beat later — approving, or rejecting when
told to. A **fake consumer over a real mechanism**: it goes through the same callback
endpoint a real service would, so what a reviewer sees in the running UI is the actual
asynchronous lifecycle rather than a claim about one.

Without this, the asynchronous design is invisible in a demo: every Transfer sits `PENDING`
forever and the reviewer has to take the state machine on faith.

**Consequence that must be honoured:** this consumer is **off in tests**, or it races the
tests' own Verdicts and produces intermittent failures that look like real bugs.

**Blocked by:** 21, 28

**Status:** ready-for-agent

- [ ] Under the demo profile, a requested Transfer settles by itself a beat later
- [ ] It can be configured to reject, and a rejected Transfer releases its reservation
- [ ] It reports Verdicts through the real callback endpoint, secret and all
- [ ] It is inactive under the test profile, and this is asserted rather than assumed
