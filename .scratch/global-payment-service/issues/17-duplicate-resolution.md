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

**Status:** done

- [x] Repeating a succeeded key and payload returns the original `201` result, not a
      second Transfer
- [x] Repeating an in-progress key returns `409` with `Retry-After` under its own type URN
- [x] Reusing a key with a different payload returns `409` with no `Retry-After` under a
      distinct type URN
- [x] Retrying a failed key executes and produces a Transfer
- [x] Phase three commits the reservation, the Transfer and the `SUCCEEDED` status together
- [x] Duplicate resolution runs before phase two

---

## Comments

### Built, 2026-09-06

Twelve files, seven of them new. The design record is
[`docs/design-decisions/17-duplicate-resolution.md`](../../../docs/design-decisions/17-duplicate-resolution.md);
this is the short version.

```
src/main/java/hu/bankmonitor/payments/idempotency/IdempotentExecution.java        (new)
src/main/java/hu/bankmonitor/payments/idempotency/ClaimedExecution.java           (new)
src/main/java/hu/bankmonitor/payments/idempotency/RequestInProgressException.java (new)
src/main/java/hu/bankmonitor/payments/idempotency/IdempotencyKeyReusedException.java (new)
src/main/java/hu/bankmonitor/payments/idempotency/IdempotencyRecordRepository.java
src/main/java/hu/bankmonitor/payments/idempotency/package-info.java
src/main/java/hu/bankmonitor/payments/transfers/CreateTransferRequest.java
src/main/java/hu/bankmonitor/payments/transfers/TransferController.java
src/test/java/hu/bankmonitor/payments/idempotency/WhatARepeatOfAKeyGetsBackTest.java (new)
src/test/java/hu/bankmonitor/payments/transfers/WhatMakesTwoTransferRequestsTheSameTest.java (new)
src/test/java/hu/bankmonitor/payments/transfers/RetryingATransferRequestMovesMoneyOnceTest.java (new)
src/test/java/hu/bankmonitor/payments/transfers/TransferRequestContractTest.java
```

`./mvnw -o test` — 213 tests, 0 failures, of which 25 are this slice's, across three
layers: the port against the table, the wire contract with the port stubbed, and the whole
thing end to end over a running server.

**The last checkbox is satisfied by construction, not by a test that names it.** Duplicate
resolution is the first thing `executeOnce` does, and phase two does not exist yet, so
"runs before phase two" cannot be asserted directly. What is asserted instead is the
property the ordering exists for: *a duplicate never runs the operation*, pinned by a run
counter in `WhatARepeatOfAKeyGetsBackTest` and by the unchanged reserved balance in
`aRepeatIsAnsweredFromTheFirstRequestAndReservesNothingMore`.

### Two scope calls, both argued in the design record and neither confirmed

**The port takes a fourth parameter.** §3 quotes
`executeOnce(String, String, Supplier<T>)`; what ships takes a `Class<T>` too, because
replaying a stored `201` means decoding stored text back into the caller's own response
type and only the caller knows what that type is. The alternatives — hand back the raw
text, or store a serialised Java object — are in the record with why each is worse.

**Phase two has no slot.** Not an omission of §4's ordering but a refusal to declare a
surface nothing calls, the same rule that kept the port out of ticket 16. The consequence
is real and named here rather than discovered later: `executeOnce` runs the caller's
`Supplier` *inside* the phase-three transaction, so ticket 26 cannot add an
outside-transaction phase without changing the exported interface. That is 26's cost, taken
knowingly against an interface no caller depends on yet.

### For ticket 18

- **The reclaim loser has no test, deliberately.** Two retries of a `FAILED` key race,
  `reclaimFailed`'s guarded update matches for one, and the loser is answered
  `RequestInProgressException`. Single-threaded that branch is unreachable — one caller
  always wins its own uncontended update — and mocking `IdempotencyClaims` to return
  `false` would assert that the `if` was typed correctly and nothing about the race. It is
  18's subject exactly.
- The end-to-end `409`-in-progress is likewise unprovokable single-threaded; the wire
  contract for it is asserted with the port stubbed.
- `RetryingATransferRequestMovesMoneyOnceTest` cleans `idempotency_records` by hand in
  `@BeforeEach`/`@AfterEach` because `ReservationScenario` does not. Whatever 18 builds for
  concurrent fixtures should absorb that.

### Review, 2026-09-06

`/mattpocock-skills:code-review` against `8b55553`, both axes. All six checkboxes verified
against the code rather than against the write-up. Four things changed as a result.

**The correctness one: `runUnderTheClaim` caught `RuntimeException`, so an `Error` escaped
the release.** An OOM or a `StackOverflowError` out of the operation would skip
`markFailed` and leave the row at `IN_PROGRESS` — the one state this whole path exists to
prevent, reached by the one throwable the `catch` did not name, with no sweeper to undo it.
Now `catch (Throwable)`: the `Error` is rethrown untouched because handling it is not this
class's business, but releasing the claim is. Narrow in likelihood, and the general fix
costs less than the narrow one.

**Both new exceptions carried a field nobody could read.** `idempotencyKey` plus a getter
on each, with no caller anywhere in `src/` and none possible: both handlers ignore the
parameter, and each class's own Javadoc says the key is deliberately not echoed back. Every
other exception in this repo carries state *because* a handler reads it out into the
problem document. Dropped; the `super(...)` message still keeps the key for the log, and
deleting the field collapsed the two classes' duplication to a one-line constructor each.

**One test statement was concatenating its key into SQL** where every sibling in the file
parameterises. Now a `PreparedStatement`.

**One scope creep in the documentation.** `deferred.md`'s *client retry semantics* entry
had been edited to declare the `409`-vs-`422` question "answered rather than open" — a
decision ticket 17 was not asked to make. The ticket's table required `409` for both and
that is all that shipped; the entry now records what shipped and leaves the status-code
half open, with the client retry policy it would be decided alongside.

**Three findings pushed back on, and answered from the design record rather than acted on.**
The port's fourth parameter and the absent phase two are the two scope calls above, both
already argued with their rejected alternatives. The third is that `replayOrReclaim` returns
an empty `Optional` to mean "you hold the claim, go execute" — a control-flow signal in a
value, which is a fair reading; the alternative is a sealed type for three outcomes, and it
is written up rather than built because the port has one caller. A fourth, `Retry-After: 1`
having no spec backing, is true and already has its own section.

The four mutations in the design record's coverage table were re-run after these changes
and are still killed at two layers each.
