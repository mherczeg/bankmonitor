# 08: The Account, with its balance and what is committed of it

**What to build:** The atomic entity of this context. An Account holds a balance in a
single Currency, fixed at creation and immutable thereafter. Nothing owns it — no `User`
is modelled, deliberately.

It carries **two** balance figures: the balance itself, and the Reserved Amount — the
portion committed to Transfers that have not yet reached a terminal state. Available
Balance is their difference, and it is what an overdraft check tests against. Keeping
both means an Account's own row answers "how much of this is spoken for" without querying
the Transfers.

Ships its own migration (`V1__accounts.sql`). With `validate` on, the entity and the
table must agree on every column name — including the two the Money embeddable generates
for each figure — and a mismatch fails at startup naming the column.

**Blocked by:** 02, 06

**Status:** ready-for-agent

- [ ] The accounts table ships as this slice's migration
- [ ] The entity carries a balance, a Reserved Amount and an immutable Currency
- [ ] The application starts with `validate` on, proving entity and table agree
- [ ] A JPA-layer test round-trips an Account and asserts both Money figures map to the
      expected columns
