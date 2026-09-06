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

**What ticket 16 left you.** The claim mechanics — `claim`, `reclaimFailed`,
`markSucceeded`, `markFailed` on `IdempotencyClaims` — plus `IdempotentExecution` itself
still to declare, and `findByIdempotencyKey`, which is what the table above's first column
is read through. Three things to know before you start:

- `markSucceeded` belongs **inside** the phase-three transaction; `markFailed` belongs
  **after** the failing one has ended, not in a `catch` within it. Reasons in
  [16's design record](../../../docs/design-decisions/16-idempotent-execution.md).
- `markFailed` throws when the key names no row, and it runs while an exception is already
  propagating. Report the original and attach this one as suppressed.
- **The table forecloses one option you may want.** A check constraint ties `response_body`
  to `SUCCEEDED` in both directions, so a `FAILED` row cannot retain a response. If storing
  a terminal failure's response body turns out to be the right model — see *Terminal vs
  retryable transfer failures* in [deferred.md](../../../docs/deferred.md) — relax the
  constraint in a `V4` migration. Ticket 16 kept it deliberately rather than weakening a
  currently-true invariant for a caller that did not exist yet.

**Status:** ready-for-agent

- [ ] Repeating a succeeded key and payload returns the original `201` result, not a
      second Transfer
- [ ] Repeating an in-progress key returns `409` with `Retry-After` under its own type URN
- [ ] Reusing a key with a different payload returns `409` with no `Retry-After` under a
      distinct type URN
- [ ] Retrying a failed key executes and produces a Transfer
- [ ] Phase three commits the reservation, the Transfer and the `SUCCEEDED` status together
- [ ] Duplicate resolution runs before phase two
