# 28: Telling the rest of the architecture what happened

**What to build:** The two event types this service emits, each written to the outbox in
the same transaction as the change that caused it.

- **A settled Transfer**, so Notification Center and Fraud Detection learn about every
  Transfer that settles without polling us.
- **A Check requested**, so a Check service is *pushed* the work along with the Transfer's
  payload rather than having to call back for the amount.

Pushing Checks through the same outbox was a deliberate choice over letting Check services
poll: polling costs a query API, cursor semantics and claim semantics **in addition to**
the outbox, which would still be needed.

**Event fatness is deliberately asymmetric.** Outbound events to services carry the
payload, because a service calling back for the amount is exactly the coupling that
rejecting polling was meant to avoid. The browser-facing stream (ticket 30) carries almost
nothing — the browser can call our API.

**Blocked by:** 20, 27

**Status:** ready-for-agent

- [ ] Settling a Transfer writes its event in the same transaction as the Settlement
- [ ] Creating a Transfer writes one Check-requested event per required Check, in the
      Transfer's own transaction
- [ ] The Check-requested event carries the payload a Check service needs to decide
- [ ] A rolled-back Settlement leaves no event
- [ ] An end-to-end test covers create → outbox → Verdict → settled → outbox
