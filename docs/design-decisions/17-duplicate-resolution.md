# Ticket 17 — what a duplicate request actually gets back

The port §3 has been quoting since before the first line of code, and the four
answers §5 tabulates, reached through it. Ticket 16 built the claim mechanics and
the transaction boundaries; this is the class that puts them in an order, the
endpoint that calls it, and the five choices the ordering did not settle on its
own.

Touches §3, §4, §5 and §18 of the [initial decisions](00-initial-decisions.md).

---

## The port takes a fourth parameter, and the stored response is why

§3 has quoted one signature from the beginning:

```java
public interface IdempotentExecution {
    <T> T executeOnce(String key, String payloadHash, Supplier<T> operation);
}
```

What ships takes a `Class<T> responseType` as well, and it is not decoration.
A replay does not come from the operation — it comes out of a `varchar` column
written by a request that finished minutes ago and is no longer anywhere in
memory. Something has to say what to read that text back as, and `T` cannot:
erasure means the method has no access at runtime to the type its own signature
is generic over.

**Rejected — return the stored text and let the caller decode it.** It removes
the parameter and puts the same argument at every call site, because the caller
now needs `TransferResponse.class` to parse what it was handed. Worse, it gives
the two paths through one method two return types — the operation's `T` on a
first request and a `String` on a repeat — so the seam's whole promise, that a
retry is indistinguishable from a first call, stops holding at the seam itself.

**Rejected — store the response as a serialised Java object.** It would carry
its own type and need no parameter. It also makes every stored row unreadable to
anything but this JVM, ties the column to a class name that refactoring changes,
and is a deserialisation gadget in a table clients can write keys into.

§3 is left as written rather than corrected. It is a sketch of a seam, and the
seam it sketched is the one that got built — the parameter is what the sketch did
not know, not something the sketch got wrong. §4's struck clause, which ticket 16
had to correct in place, was a different thing: a claim about behaviour that
turned out to be untrue.

---

## Phase two has no slot in the port, which is the no-surface-without-a-caller rule again

§4's three phases are claim, resolve the FX rate, reserve. `executeOnce` opens
the phase-three transaction itself — it has to, because
`IdempotencyClaims.markSucceeded` is `MANDATORY` and has to join the transaction
the money moves in (ticket 16) — and the caller's operation runs inside it. So
there is nowhere in this port to put phase two, and no parameter for it.

It was left out rather than invented, on the rule this repository has followed
since [ticket 08](08-account-entity.md) and that ticket 16 used to defer this
very port: no surface is declared without a call site. There is nothing to
resolve today, because a same-Currency Transfer needs no Exchange Rate. Ticket 26
is the first caller with a phase two, and it is the ticket that should shape the
parameter around what it actually needs — a second `Supplier` running outside the
transaction, most likely, but that is a decision to make with the FX call in hand
rather than against a guess at it.

**The ticket's *"duplicate resolution runs before phase two"* checkbox is
satisfied by construction rather than by a phase-two argument.** Resolution
happens above everything: the claim is attempted first, and a duplicate is
answered or refused before the port reaches anything a caller handed it. Whatever
ends up between the claim and the transaction, a duplicate will not reach it
either. That is what the tests assert — *a duplicate never runs the operation* —
which is the durable form of the claim.

---

## `TransactionTemplate` for phase three, not `@Transactional`

The transaction the operation and the `SUCCEEDED` flip share is opened by hand.
Two reasons, and each is enough on its own.

**Self-invocation.** `@Transactional` is proxy-based: a method of a class calling
another method of *the same* class does not go through the proxy, so the
annotation does nothing at all. `executeOnce` decides whether to run the
operation, so the annotated method would have to be a second method here, called
from the first — the exact shape that silently has no transaction. What makes it
worse than the usual version of this trap is that it would not be silent for
long: `markSucceeded` is `MANDATORY`, so the failure surfaces as an
`IllegalTransactionStateException` from a method three layers down, which is a
long way from the missing annotation that caused it.

**`markFailed` has to run after the transaction has ended.** Ticket 16 measured
what happens when it runs inside: a new transaction cannot see the locks the
suspended one holds, so it waits two seconds on the row the failing transaction
already touched and then leaves that row at `IN_PROGRESS` — the one state the
whole path exists to prevent. With a template, "after" is a `catch` block sitting
visibly outside `transaction.execute(...)`. With an annotation, "after" is a
property of a method boundary that no reader can see and any refactor can move.

That `catch` takes `Throwable`, not `RuntimeException`. Handling an `Error` is
not this class's business and it is rethrown untouched — but *releasing the
claim* is, and an `Error` that slipped past the release would leave the row at
`IN_PROGRESS` exactly as the two-second timeout did. Nothing sweeps stranded
keys, so the promise that a failed key is retryable would come to depend on
which unchecked throwable arrived, which is not a distinction any caller can
make. The review that found this asked the narrow version — an OOM inside the
reservation — and the general answer is cheaper than the narrow one.

---

## An absent row rethrows the violation, which is what replaces matching on a constraint name

`claim` discovers a duplicate by letting the insert fail, so the duplicate path
starts from a `DataIntegrityViolationException` — and that exception is not
proof that *this key* was the problem. A `payload_hash` of the wrong length or
any other constraint on the row raises the same type.

Ticket 16 named the unique constraint `IDEMPOTENCY_RECORDS_KEY_IS_UNIQUE` partly
so that ticket 17 could tell the two apart by matching on it. This ticket does
not, and reads the row instead: if `findByIdempotencyKey` finds nothing, the
original violation is rethrown unchanged. The row has to be read on the duplicate
path anyway — it is the only thing that can tell "wait and retry" from "you have
made a mistake" — so the guard costs nothing, and its absence is what would be
expensive: a `409` reporting a claim that does not exist, for an insert refused
by something else entirely.

**Rejected — matching the constraint name.** It works, and it ties this class to
a string that H2 and PostgreSQL surface differently inside a message that is not
part of either one's contract. The named constraint keeps its own job in ticket
16's test, where it asserts *which rule* was broken; it is just not load-bearing
here.

---

## The payload hash is a method on the request, over the parsed record

`CreateTransferRequest.payloadHash()` — SHA-256 over the three components,
separated, hex-encoded to the 64 characters `payload_hash varchar(64)` holds.

**The parsed request is hashed, not the bytes that arrived.** Two postings of the
same Transfer that differ in whitespace or member order are the same intent, and
hashing the body would make them two hashes — turning an operator's honest retry
into `idempotency-key-reused`, a refusal §5 says must never be retried. It also
keeps the request stream out of it: reading a body twice needs a
content-caching filter, and the request pipeline is exactly where §3 refused to
put any of this.

The separator is not optional and has a test of its own: without it, `5 → 9` of
100 and `5 → 91` of 0 are one payload, and so are a Transfer and its reverse.

**Rejected — a shared hashing helper in `common` or in `idempotency`.** There is
one caller, and what varies between callers is not the algorithm but *which
fields make two requests the same* — a question about the `transfers` payload
that the next endpoint to take a key will answer differently for its own. A
generic hash over "whatever was posted" would only look reusable, and would have
to be handed the fields anyway.

---

## `Retry-After: 1`, chosen here because nothing else chose it

§18 requires the header on the in-progress `409` and requires its absence on the
key-reuse one. Neither §18 nor §5 fixes a value.

One second. What a client meeting `request-in-progress` is waiting on is a single
database transaction that reserves funds and writes two rows — milliseconds of
work — so a larger number would be telling a client to sit out an operation that
finished long before the header was read. It is an honest reading of what the
operation costs rather than a policy, and it lives as a constant on the
controller for the reason ticket 14 put the whole exception-to-status mapping
there: the endpoint is what knows what the work behind it costs.

The two handlers are on `TransferController` rather than in a
`@ControllerAdvice`, on [ticket 14](14-request-transfer-endpoint.md)'s measured
precedent — an advice would have to out-order `ProblemDocumentAdvice`, whose
`Exception` handler answers anything it reaches with `500`. Nothing about that
was reopened. They return `ResponseEntity<ProblemDetail>` where the four `422`s
return a bare `ProblemDetail`, and only because a returned `ProblemDetail` has
nowhere to carry a header.

---

## One accepted coverage gap, named rather than faked

**The reclaim loser has no test.** Two retries of a `FAILED` key race for it,
`reclaimFailed`'s guarded update matches for exactly one of them, and the loser
is answered `RequestInProgressException` — correctly, because by then the winner
is holding the claim. That branch cannot be provoked single-threaded: one caller
always wins its own uncontended update.

It is [ticket 18](../../.scratch/global-payment-service/issues/18-idempotency-concurrency-tests.md)'s
subject exactly, and it is written down here rather than covered by a test that
mocks `IdempotencyClaims` to return `false` — which would assert that the `if`
was typed correctly and nothing about the race the `if` exists for.

Everything else in the outcome table is covered at two layers, and both were
checked by mutation rather than assumed:

| Mutation | What failed |
|---|---|
| the payload-hash guard never fires | `WhatARepeatOfAKeyGetsBackTest` ×3, `RetryingATransferRequestMovesMoneyOnceTest.aKeyReusedForADifferentTransferIsRefused` |
| a failed operation never releases its claim | `WhatARepeatOfAKeyGetsBackTest` ×3, `RetryingATransferRequestMovesMoneyOnceTest` ×2 |
| a `SUCCEEDED` claim runs the operation again | `aRepeatOfASucceededKeyReplaysTheStoredAnswer`, `aRepeatIsAnsweredFromTheFirstRequestAndReservesNothingMore` |
| the in-progress refusal carries no `Retry-After` | `TransferRequestContractTest.refusesAKeyWhoseWorkIsUnfinished` |

The three layers are deliberate and not redundant. The port's test places the
claim a repeat starts from straight into the table, so each row of §5's outcome
table is one line of setup. The contract test stubs the port away and asserts the
wire. **`RetryingATransferRequestMovesMoneyOnceTest` places nothing**: every state
a repeat meets was left behind by an earlier request through the same endpoint,
which is the only arrangement that can be wrong in the way production would be —
and the only place where §4's bundling is visible, as *all three of the
reservation, the Transfer and the `SUCCEEDED` flip are there, or none of them is*.

---

## Two things the mock in the contract test made explicit

`TransferRequestContractTest` needed a `@MockitoBean IdempotentExecution` with a
pass-through answer, or every pre-existing test in it would have asserted against
the `null` an unstubbed mock returns. Two notes for whoever edits it next:

- **Stubbing over an `Answer` has to use `willX().given(mock)`, not
  `given(mock.x())`.** The second form *calls* the mock to record what to stub,
  and calling it runs the standing pass-through against the nulls the argument
  matchers stand in for. Measured here as three `NullPointerException`s inside
  the `@BeforeEach`, reported against a lambda in the setup rather than against
  the test that re-stubbed.
- The pass-through means the endpoint's existing tests now exercise one more
  seam than they did, without asserting anything about it. That is the right
  trade at this layer: what they are about is the wire, and the port is stubbed
  for the same reason `FundsReservation` is.

---

## What this ticket did not build

- **A sweep for claims stranded at `IN_PROGRESS` by a crash.** Still deferred,
  still for ticket 16's reason: it needs a claim age, which is a column and a
  scheduled job no ticket in the plan has. [deferred.md](../deferred.md) carries
  it.
- **Relaxing the `response_only_when_succeeded` constraint.** Ticket 16 flagged
  it as this ticket's to reconsider if a terminal failure's body should be
  stored and replayed. It should not, and for a reason §5 already gives: this
  port governs *reservation-time* failures only, and every one of them is
  retryable. A `FAILED` row keeping a response would be storing an answer that
  §5 says must never be replayed. The constraint stays, and the `V4` migration
  ticket 16 anticipated is not needed.
- **A `Retry-After` on anything else.** The FX provider's `503` will want one
  (§27), and it is ticket 26's to choose — the number depends on what the
  provider costs, which is the same reasoning that put this one on the endpoint.
