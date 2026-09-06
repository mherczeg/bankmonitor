# 26: Cross-Currency Transfers, end to end

**What to build:** A Transfer between Accounts in different Currencies. This is where the
conversion (07), the client (25) and the three-phase request (17) meet.

**The Exchange Rate is fetched in phase two** — after the key is claimed, before any lock is
taken. That placement is the whole reason the locking design is viable: a slow provider
must never be something a database lock waits on.

**The rate is locked onto the Transfer** for its life, stored with the timestamp it was
fetched, so the figure the operator was shown is the figure that settles and a settled
conversion is auditable. Re-quoting at Settlement is deferred — it is a business decision
about who carries FX movement risk, not a technical one. Ships as a migration adding the
rate and timestamp columns.

**A same-Currency Transfer must not touch the provider at all**, so an unrelated outage
cannot block it.

Failure paths:

- A conversion rounding down to zero Minor Units is refused with `422` — no Transfer may
  debit the source and credit nothing.
- Exhausted retries return `503` with a type URN naming the provider and a `Retry-After`,
  so the client knows the failure is not theirs and is worth retrying.
- **The Idempotency Key is left `FAILED`**, so resubmitting with the *same* key executes
  properly rather than replaying a failure. Failing to the caller is not giving up —
  idempotency is what makes the lean retry policy complete.

**Blocked by:** 07, 17, 25

**Status:** done

- [x] A cross-Currency Transfer converts at the fetched rate and credits the destination in
      its own Currency
- [x] The rate and its fetch timestamp are stored on the Transfer and returned by the API
- [x] A same-Currency Transfer makes no call to the provider
- [x] A conversion rounding to zero is `422`
- [x] Provider failure returns `503` with the provider's type URN and a `Retry-After`
- [x] After a provider failure the key is `FAILED`, and resubmitting the same key succeeds
- [x] The rate is fetched with no transaction open and no lock held
