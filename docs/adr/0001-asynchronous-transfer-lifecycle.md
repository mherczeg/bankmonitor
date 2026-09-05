# 1. Transfers have an asynchronous lifecycle

**Status:** Accepted — 2026-09-05

## Context

The task describes a payment gateway that moves money between accounts and
forwards events "a külvilág felé" — to the outside world — naming Fraud
Detection as *one example* of the services involved. Nothing in the spec says a
transfer must complete within its HTTP request, and nothing says it must not.

Two signals in the spec point away from a synchronous design. Neither is
conclusive alone:

- **Fraud Detection is named as an example of a class.** Some members of that
  class are notification consumers, but some are approvers — fraud holds,
  manual review, account-holder signing. Real payment systems authorise, clear
  and settle, with gates between those steps that can suspend a payment for
  hours.
- **The idempotency requirement defines a "still being processed" state
  returning `409`.** In a synchronous design that state exists for the few
  milliseconds a request is in flight, and is barely observable. The spec
  treats it as a state a client will realistically encounter.

The decisive question was not "is money moving slow?" but "what does it cost to
add the first approver?" In a synchronous design a transfer either completes or
fails inside one request; introducing a party that can say *wait* means
inventing pending state, releasing funds that were already moved, and
restructuring the endpoint contract — a rebuild, not an extension.

## Decision

A transfer is created `PENDING` and reaches `SETTLED`, `REJECTED` or `EXPIRED`
later. Funds are **reserved** on the source account at request time; the money
**moves** at settlement.

Orchestration is centralised: the transfer service owns the state machine and,
in the same transaction that creates the transfer, writes one Check Ledger row
per required check. The transfer settles when no row is pending and none is
rejected, and rejects the moment any row is rejected. Verdicts arrive from
outside; the orchestrator does not care whether they came from a service or a
human.

See [design-decisions.md](../design-decisions.md) §7–§15 for the mechanics.

## Consequences

**Gained**

- **Adding an approver is a policy line and a ledger row**, not a
  restructuring. That is the whole point of the decision.
- **"Why is this stuck" is queryable.** The Check Ledger is the pending-state
  UI, the audit trail and the test seam at once — one structure paying for
  itself three times.
- **Human and automated approvers are indistinguishable** to the orchestrator.
  A back-office approval screen and a fraud service report through the same
  domain operation.
- **The spec's `409` becomes a real state** rather than a millisecond race —
  something a client will actually hit and that the frontend actually renders.
- **"Undo a payment" became "don't make one."** Reserving funds and settling
  later means a rejected transfer never moved money, so there is no
  compensating transaction to write and no window in which a reversal can fail.

**Paid**

- **Reserved balance becomes domain state** — a second field on `Account`, and
  every read of "can this account afford it" goes through
  `balance - reservedAmount` (§13).
- **A scheduled expiry reaper must exist** (§14). Without it a check that never
  answers reserves funds forever, and `PENDING` has no exit that does not
  depend on an external service behaving.
- **Verdicts must be idempotent, via conditional updates.** A check reporting
  approval twice must not advance the transfer twice, and a verdict arriving
  after expiry must not settle it (§5, §8, §14).
- **A second outbox event type**, `CheckRequested` (§11).
- **An inbound verdict endpoint** — the first security-sensitive surface in the
  app, which is why `/internal/**` sits behind a shared secret (§10). A
  synchronous design has no such surface.
- **The FX rate must be locked at request time** (§15), because fetching it
  after every approval leaves an approved transfer that cannot settle and no
  caller to tell.
- **The frontend needs a live channel and a per-transfer route.** Pending state
  has to be visible and has to survive a refresh (§17, §21).
- **Tests become multi-transaction and multi-threaded.** Most of the interesting
  assertions are about database behaviour across transaction boundaries, not
  about pure functions (§25).
- **This is knowingly the larger build**, chosen inside a 10–12 hour budget.

## Alternatives

**Synchronous transfer — rejected.** Debit and credit inside the request; the
call returns the completed transfer. Simpler by every measure and adequate for
the literal requirements. Rejected because it cannot grow an approver without
being rebuilt (above), and because moving money first means every failure needs
a reversal, where the asynchronous model has nothing to undo.

**Choreography — rejected.** No central orchestrator; services react to each
other's events and the transfer settles when the last one is happy. Rejected
because there is then no record of what a transfer is waiting on: "why is this
stuck" has no queryable answer, the frontend has nothing to render, and tests
have no seam. The Check Ledger is precisely what choreography gives up.
