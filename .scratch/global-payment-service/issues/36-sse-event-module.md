# 36: Stream events as cache invalidations

**What to build:** A pure function from a stream message to the list of query keys it
invalidates. React's only job afterwards is to iterate that list and invalidate — no
merging, no reconciliation, no ordering logic.

This is the payoff of the backend deciding a message carries only a type and a Transfer ID.
It also makes the usual testing yak-shave disappear: there is no event-source polyfill to
install, no streaming mock to build, and no flake, because the thing worth testing is a
function over a plain object.

**Blocked by:** 31

**Status:** done

- [x] A pure function maps a stream message to the query keys to invalidate
- [x] A settled Transfer invalidates that Transfer, the transfers list and the accounts list
- [x] An unknown event type yields no keys rather than throwing
- [x] The module imports nothing from React and needs no event-source polyfill to test
