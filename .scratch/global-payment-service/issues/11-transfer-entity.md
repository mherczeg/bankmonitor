# 11: The Transfer and its states

**What to build:** A Transfer is a request to move an amount of Money from one Account to
another, possibly across Currencies, and it has a lifecycle rather than an outcome:
`PENDING` while its Checks are outstanding, then terminally `SETTLED`, `REJECTED` or
`EXPIRED`. Only `SETTLED` moves money; each terminal state releases the Reserved Amount.

This ticket lands the entity, its repository and its migration (`V2__transfers.sql`) —
source and destination Account references, the amount in the source Currency, the credited
amount in the destination Currency, the status, and the timestamps. The columns the later
slices need (the Check deadline, the locked Exchange Rate and its fetch timestamp) belong
to *those* slices' migrations, not this one.

Note the vocabulary: there is no "Transaction" entity here. "Transactions" is the label of
a screen; a recorded Transfer is still a Transfer.

**Blocked by:** 02, 08

**Status:** ready-for-agent

- [ ] The transfers table ships as this slice's migration
- [ ] Status is the closed set `PENDING`, `SETTLED`, `REJECTED`, `EXPIRED`
- [ ] The entity carries both the debited and the credited amount as Money
- [ ] The application starts with `validate` on, proving entity and table agree
- [ ] A JPA-layer test round-trips a Transfer in each status
