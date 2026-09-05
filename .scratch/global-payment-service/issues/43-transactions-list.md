# 43: The Transactions screen

**What to build:** The third required screen: one list showing Transfers in **every** state,
with a status column and an optional status filter.

Showing every state is not a liberty taken with the requirement — under an asynchronous
lifecycle a `PENDING` Transfer that appeared on no list would make the only list screen
misleading. The filter is what lets an operator narrow to settled Transfers when that is
what they want.

Note the vocabulary: the screen is called Transactions, and every row on it is a Transfer.
This context has one word for the thing.

**The client-side filter is a rendering convenience, not a boundary.** Every list returns
everything to anyone — a consequence of the authentication and ownership deferrals, recorded
as such.

Refetch on window focus, like the Accounts screen, since this route does not consume the
event stream.

Carries its own browser spec.

**Blocked by:** 15, 40

**Status:** ready-for-agent

- [ ] Transfers in all four states appear, with a status column
- [ ] The status filter narrows the list and can be cleared
- [ ] Amounts render in each Transfer's own Currencies, correctly formatted
- [ ] Returning focus to the window refetches
- [ ] Rows link to the Transfer's own page
- [ ] A browser spec covers the mixed-state list, the filter and the focus refetch
