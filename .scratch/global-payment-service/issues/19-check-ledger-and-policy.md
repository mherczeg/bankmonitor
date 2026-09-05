# 19: The Check Ledger and the decision it drives

**What to build:** The record that makes "why is this Transfer still pending" a question
with an answer. A Check is a condition a Transfer must satisfy before it can settle —
fraud screening, manual approval — answered by a party outside this context. A human
approver and an automated service are the same kind of thing here.

Three pieces:

- **The Check Ledger**: one row per Check a Transfer requires, recording the Check and its
  Verdict (approved, rejected, or not yet answered). Ships its own migration.
- **The policy** that decides which Checks a Transfer requires, and writes those rows **in
  the same transaction as the Transfer**, so a Transfer can never exist without its
  checklist. Adding a new Check type should be a line in the policy and a row in the
  ledger — that extensibility is the whole reason the asynchronous lifecycle was chosen.
- **The decision function**, pure: `decide(ledgerRows) → Settle | Reject | Wait`. Settle
  when no row is outstanding and none rejected; Reject on the first rejection; Wait
  otherwise. Rejection wins immediately — there is no point waiting on the remaining
  Checks once one has said no.

Keeping `decide` free of I/O is a design instruction, not a testing one: the same logic
inside a service that also touches the database is not testable at all.

**Blocked by:** 11, 13

**Status:** ready-for-agent

- [ ] The check ledger table ships as this slice's migration
- [ ] The policy writes ledger rows in the Transfer's own transaction
- [ ] A Transfer with no ledger rows is impossible to create
- [ ] `decide` is a pure function over ledger rows, with no repository or clock
- [ ] Unit tests cover all-approved, one-rejected, mixed, and none-answered — no Spring
      context
