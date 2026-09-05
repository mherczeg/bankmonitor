# 10: List Accounts, and have some to look at

**What to build:** Every Account with its balance *and* its Available Balance, so an
operator can both see what an Account holds and tell how much of it is already committed
to in-flight Transfers. Available Balance is derived, never stored as a third figure.

Plus demo Accounts to look at: a handful in different Currencies, created by a dev-profile
runner at startup. **Seed data is not schema** — putting it in a migration would run it
inside the test suite, where every test would then start from someone else's fixtures.

This endpoint is also the first meaningful shape in the OpenAPI document that the frontend
generates its types from (ticket 32).

**Blocked by:** 08

**Status:** done

- [x] Listing returns every Account with balance and Available Balance
- [x] Available Balance is computed from the balance and the Reserved Amount
- [x] Demo Accounts in at least two Currencies appear under the dev profile
- [x] No seed data runs under the test profile
