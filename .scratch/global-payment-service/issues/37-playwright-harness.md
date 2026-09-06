# 37: The browser test harness

**What to build:** The second of the two test-entry seams: a real browser against a
scripted network. Real focus and blur, real navigation, real form behaviour — the things a
simulated DOM gets wrong.

Two pieces, and the reasoning behind each is worth keeping:

- **Route-level HTTP mocking**, not a service worker. No extra build mode, no worker to
  register, and the mocks live in the spec that depends on them rather than in a shared
  fixture nobody can safely change.
- **A fake event source installed by an init script that runs before app code.** The route
  mocker can only fulfil a request with a complete string or buffer — there is no streaming
  body — so an event cannot be pushed mid-test, which is exactly what asserting
  `PENDING → SETTLED` requires. The fake makes the sequence deterministic with no waiting on
  timers: assert the row reads pending → re-route the Transfer endpoint so **truth changes**
  → dispatch a message on the fake source → assert the row reads settled.

Ship helpers for both, plus fixtures typed from the generated API types, so a mock that
drifts from the backend is a build failure.

Later screen tickets each carry their own specs; this ticket carries the machinery and one
smoke spec proving it works.

**Blocked by:** 31

**Status:** done

- [x] Playwright runs headless from a documented command and in a single browser
- [x] A helper scripts API responses per test, typed from the generated API types
- [x] An init script replaces the browser's event source before app code runs
- [x] A smoke spec drives a dispatched message end to end and asserts a re-render
- [x] No spec waits on a fixed timeout
