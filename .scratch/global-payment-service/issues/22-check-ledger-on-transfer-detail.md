# 22: Showing what a Transfer is waiting on

**What to build:** The single-Transfer response carries its Check Ledger: which Checks the
Transfer requires and how each has been answered so far. That turns "why is this stuck"
from a support question into a field on a screen.

This is what makes the Check Ledger the pending-state UI, the audit trail and the test
seam at once, rather than an internal bookkeeping table.

**Blocked by:** 15, 19

**Status:** done

- [x] Fetching one Transfer returns its Checks with each one's Verdict or its absence
- [x] A `PENDING` Transfer's outstanding Checks are distinguishable from answered ones
- [x] The list endpoint is unchanged — this detail belongs to the single-Transfer response
- [x] The response shape appears in the OpenAPI document
