# 42: The Transfer page updates itself

**What to build:** The Transfer page subscribes to the event stream, and a message about
this Transfer invalidates the queries that describe it — nothing more. The event module
(ticket 36) decides which keys; React iterates and invalidates; the query client refetches;
the page re-renders from the API's truth.

This is the only route that consumes the stream, deliberately. The Accounts and
Transactions screens are covered by refetch-on-focus instead, which is why a balance the
operator just watched change is not stale when they navigate to the Accounts list.

**Reconnection converges by refetching on open** rather than by replaying missed events —
the backend deliberately has no catch-up, so a dropped connection costs nothing and the
recovery path is the first-load path.

The browser spec drives it through the fake event source from ticket 37: assert the page
reads pending → re-route the Transfer endpoint so **truth changes** → dispatch a message →
assert the page reads settled. That exercises the whole thin-event → invalidate → refetch →
render design with no waiting on timers.

**Blocked by:** 30, 36, 41

**Status:** ready-for-agent

- [ ] The Transfer page updates from `PENDING` to its terminal state without a refresh
- [ ] Live updating still works after a page refresh — the subscription is not tied to
      component state left behind by navigation
- [ ] The stream opening triggers a refetch, so a missed event costs nothing
- [ ] A dropped connection reconnects and converges
- [ ] Leaving the page tears the subscription down
- [ ] A browser spec drives pending → settled through a dispatched message with no fixed
      timeout
