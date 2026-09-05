# 15: Reading Transfers back

**What to build:** One list endpoint returning Transfers **in every state**, with an
optional status filter, and one endpoint for a single Transfer by ID.

Showing every state is a deliberate reading of the requirement. The task asks for
"executed transfers", which was written against a synchronous model where a Transfer is
either done or never existed. Under the asynchronous lifecycle, a `PENDING` Transfer that
appears nowhere would make the only list screen actively misleading — an operator would
believe their Transfer had vanished.

The single-Transfer endpoint is what the frontend navigates to after submitting, so a
pending Transfer's state lives in the URL and survives a refresh.

No pagination — deferred, with its reasoning recorded.

**Blocked by:** 11

**Status:** ready-for-agent

- [ ] Listing returns Transfers in all four states, newest first
- [ ] The optional status filter narrows to one state and rejects an unknown value
- [ ] Fetching one Transfer by ID returns it; an unknown ID is `404` as a problem document
- [ ] Both responses carry the amounts as Minor Unit counts with their Currencies
