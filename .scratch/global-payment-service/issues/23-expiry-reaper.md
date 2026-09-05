# 23: Expiry, so unanswered Checks cannot freeze funds

**What to build:** A `PENDING` Transfer whose Checks were not all answered before its
deadline becomes `EXPIRED`, releasing its Reserved Amount without moving money. Without
this, one unresponsive Check service holds an operator's money hostage indefinitely.

Three parts:

- **A deadline on the Transfer**, set when it is requested. Ships as a migration adding the
  column.
- **A scheduled reaper** that flips overdue `PENDING` Transfers to `EXPIRED` and releases
  their reservations. Cheap to add, because the scheduled-poller machinery already exists
  for the outbox. Advancement is the same conditional update used everywhere else, so the
  reaper and an in-flight Verdict cannot both win.
- **A late Verdict is refused.** A Check approving a Transfer after it expired must not
  settle it — the reservation is already gone, and settling would move money that was
  released.

**The quote's validity window and the Check deadline are the same clock**, deliberately, so
a Transfer that outlives its Exchange Rate quote expires rather than settling on a stale
rate. Ticket 26 relies on that.

**Blocked by:** 20

**Status:** ready-for-agent

- [ ] Transfers carry a deadline, added by this slice's migration
- [ ] An overdue `PENDING` Transfer becomes `EXPIRED` and its reservation is released
- [ ] The source Account's Available Balance recovers by exactly the reserved amount
- [ ] A Verdict arriving after Expiry is refused and moves no money
- [ ] An end-to-end test covers expiry and the late Verdict, driving the clock rather than
      sleeping
