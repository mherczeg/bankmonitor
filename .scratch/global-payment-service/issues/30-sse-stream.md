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

**Status:** ready-for-agent

- [ ] One stream endpoint serves all subscribers
- [ ] A message contains only an event type and a Transfer ID
- [ ] Settlement, rejection and expiry each emit a message
- [ ] Multiple concurrent subscribers each receive every message
- [ ] A disconnecting client is cleaned up and does not leak an emitter

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
