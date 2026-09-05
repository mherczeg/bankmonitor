# 17: What a duplicate request actually gets back

**What to build:** The Transfer request becomes three phases, and repeats of a key get the
answer the task specifies.

**The three phases:**

1. **Claim** the key as `IN_PROGRESS` in its own immediately committed transaction, where
   the unique constraint serialises duplicates.
2. **Resolve** anything slow or external — with no transaction open and no locks held.
   (Same-Currency Transfers have nothing to resolve yet; ticket 26 fills this phase.)
3. **Reserve** funds, create the Transfer, *and* flip the idempotency record to `SUCCEEDED`
   with its stored response — all in **one** transaction. Bundling the status update is
   deliberate: separate commits would let a crash strand reserved funds behind a permanent
   `409`.

**Duplicate resolution happens at the top, before phase two**, so a duplicate costs
nothing:

| Existing status | Response |
|---|---|
| `IN_PROGRESS` | `409`, request-in-progress URN, **with** `Retry-After` |
| `SUCCEEDED` | replay the stored `201` verbatim |
| `FAILED` | claim by conditional update, then execute |
| different payload hash | `409`, key-reused URN, **no** `Retry-After` |

The two `409`s must be told apart by their type URN and by the presence of `Retry-After`,
because they mean opposite things to a client: "wait and retry" versus "you have made a
mistake, never retry this".

A `FAILED` key being retryable is what makes the whole design work: any reservation-time
failure is recoverable by resubmitting the *same* key.

**Blocked by:** 14, 16

**Status:** ready-for-agent

- [ ] Repeating a succeeded key and payload returns the original `201` result, not a
      second Transfer
- [ ] Repeating an in-progress key returns `409` with `Retry-After` under its own type URN
- [ ] Reusing a key with a different payload returns `409` with no `Retry-After` under a
      distinct type URN
- [ ] Retrying a failed key executes and produces a Transfer
- [ ] Phase three commits the reservation, the Transfer and the `SUCCEEDED` status together
- [ ] Duplicate resolution runs before phase two
