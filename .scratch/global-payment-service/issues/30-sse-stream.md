# 30: One event stream for the browser, carrying hints

**What to build:** A single server-sent-events endpoint — not one per Transfer — that
pushes a message whenever a Transfer's state changes.

**A message carries only an event type and a Transfer ID.** Nothing else. That is the
design decision the whole live-update story rests on: a message becomes nothing but a cache
invalidation, so the frontend never merges, reconciles or orders anything. *The stream
carries hints; the REST endpoint carries truth.*

**No catch-up**: no last-event replay, no buffer. The client refetches when the stream
opens, which converges from any missed state and reuses the first-load path it already has.
That is why a dropped connection costs nothing.

**Deferred, and consequences of earlier deferrals rather than choices of their own:** the
stream is unscoped — every subscriber sees every Transfer's events — and it is
instance-local, which is the second of the two named breaks of the single-instance
assumption.

**Blocked by:** 20

**Status:** done

- [x] One stream endpoint serves all subscribers
- [x] A message contains only an event type and a Transfer ID
- [x] Settlement, rejection and expiry each emit a message — *with one qualification:*
      settlement and rejection are covered end to end, from a recorded Verdict to a
      message read off a real socket. **Expiry is mapped and reaches a browser, but
      nothing in this application expires a Transfer yet** — ticket 23 is the reaper and
      is not built, so `EXPIRED` has no producer. A fake reaper would assert that the
      fake works, so the mapping is covered directly instead — announced from inside a
      transaction that commits, so the covered path is the one ticket 23 is told to use
      rather than the fallback. Ticket 23 inherits one obligation and no design: call
      `LifecycleHints.announce(transferId, EXPIRED)` inside the transaction that
      releases the reservation.
- [x] Multiple concurrent subscribers each receive every message
- [x] A disconnecting client is cleaned up and does not leak an emitter

**Built in [design decision 30](../../../docs/design-decisions/30-sse-stream.md)**, which
records the two things the build found: `LockedPathTouchesOnlyTheDatabaseTest` forbids the
obvious shape, so the two slices are joined by an application event delivered after commit
— which buys the ordering this ticket's no-catch-up rule requires, in the same move; and
Spring Framework 7 no longer commits the SSE response before initialising the emitter, so
an emitter nothing is written to never reaches the browser at all. The second is not a test
problem: it is what `onopen`, and therefore the refetch-on-open convergence, depends on.

## Comments

**Ticket 36 wrote this endpoint's contract ahead of it.** The browser side is built and
tested against these three names, carried as JSON on the **default** event (not as named
SSE events — see `docs/design-decisions/36-sse-event-module.md` for why):

```
{"type":"TRANSFER_SETTLED","transferId":7}
{"type":"TRANSFER_REJECTED","transferId":7}
{"type":"TRANSFER_EXPIRED","transferId":7}
```

`frontend/src/api/events.ts` ignores anything else, so a name that does not match is a
live-update feature that silently does nothing.
