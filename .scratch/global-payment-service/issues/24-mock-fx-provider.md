# 24: A stand-in Exchange Rate provider that behaves like a third party

**What to build:** A mock Exchange Rate provider that is **a real HTTP endpoint inside the
application**, profile-gated, quoting rates between EUR, USD and HUF — and configurably
flaky: it returns `503`s and it responds slowly, because that is the behaviour the task
specifies and the thing the resilience work has to survive.

A stub bean was rejected for a specific reason: a bean sits *above* the HTTP client, so
none of the timeouts, retries or error mapping being demonstrated would ever run. The
thing being demonstrated would be the thing mocked out.

**It must stay out of the application's cross-cutting layers**, or it stops being a
believable third party and becomes part of our app wearing a costume:

- security **bypassed** for its paths, not merely permitted
- a **scoped** exception handler, not the application's global one
- explicit filter URL patterns that exclude it
- out of the CORS mapping entirely

**Consequence to keep in view:** the application now calls itself over HTTP, which is what
makes virtual threads load-bearing — on a classic pool an inbound request can hold a thread
waiting for a second thread to serve its own outbound call.

**Blocked by:** 04

**Status:** ready-for-agent

- [ ] The mock serves rates over real HTTP under a dedicated profile, and is absent
      otherwise
- [ ] Its failure rate and latency are configurable
- [ ] It is excluded from the application's security, error-handling, logging and CORS
      layers, each verified rather than assumed
- [ ] Its errors are its own, not the application's problem documents
