# Initial decisions

Settled in a grilling session on 2026-09-05, **before any code was written** —
this file is the design as it stood when the first ticket was picked up, and it
is not edited as the build proceeds. What each ticket settled, corrected or
contradicted lives in its own file beside this one; see the
[index](README.md) for the map from section to ticket.

Source material for the README's architecture section. Deferrals live in
[deferred.md](../deferred.md); domain vocabulary in [CONTEXT.md](../../CONTEXT.md).

Each entry: what was decided, why, and what was rejected.
[ADR-0001](../adr/0001-asynchronous-transfer-lifecycle.md) records the one decision
that shapes all the others.

> **Verify in the first hour of building.** Two of these decisions bet that the
> ecosystem has caught up to Spring Boot 4. Both are cheap to check now and
> expensive to discover late:
>
> 1. **Hibernate maps a Java `record` as `@Embeddable`** (§16) — supported since
>    Hibernate 6.2, and Boot 4 ships Hibernate 7. If not, every entity's shape
>    changes.
> 2. **`springdoc-openapi` supports Spring Boot 4** (§26) — if not, the
>    generated-types safety net for the frontend does not exist.
>
> *Both bets won, and one of them turned up a trap — see
> [ticket 01](01-project-skeleton.md).*

---

## 1. `Account` is the atomic entity — no `User`

No owner is modelled. Every operational requirement is account-shaped:
transfers move money between account IDs, and all three screens are unscoped.
The spec's "felhasználói számla" reads as an adjective, not a modelling
instruction.

`Account` is a mutable class with a protected no-arg constructor, which is the
only shape JPA can map — a `record` compiles, boots and passes `validate`, then
throws on the first write. Adding an `owner` field later is additive and
non-breaking either way. `User` is named in `CONTEXT.md` as deliberately
unmodelled.

**Rejected — an empty `interface AccountOwner {}` stub.** Works in TypeScript,
where structural typing makes an empty interface a maximally-wide "shape TBD"
placeholder that erases at compile time. Java is nominally typed, so an empty
interface nothing implements is satisfied by *nothing*; the field can only ever
be `null`; JPA cannot map a relationship to a type without an `@Id`, so the app
fails to start; and the field leaks into the API response as a permanent
`"owner": null`.

**Rejected — an `OwnershipPolicy` seam.** A stub that names a rule asserts the
rule exists. It would read as ownership implemented permissively, not deferred.

> The `record` sentence above is a correction from **[ticket 08](08-account-entity.md)**, which measured it.

---

## 2. Authentication is stubbed, not built

No authentication scheme and no principal — nothing downstream learns who
called, so no controller signature carries identity.

Implemented with Spring Security rather than a hand-rolled filter, with each
setting justified rather than arbitrarily disabled: CSRF off (stateless JSON
API, no cookies), `STATELESS` session policy, CORS enabled (the React dev
server is a different origin), `permitAll` on `/api/**` — plus the one real
rule from §10.

**Watch out:** Spring Security runs before Spring MVC and intercepts CORS
preflight `OPTIONS` requests. Needs `.cors(withDefaults())` *and* a
`CorsConfigurationSource` bean; the MVC-level `@CrossOrigin` annotation alone
will not work. Symptom is opaque CORS failures in the browser while `curl`
works.

> **Built in [ticket 04](04-security-chain-cors.md)**, deny-by-default rather than
> permit-by-default, with three alternatives rejected in the writing.

---

## 3. Idempotency lives in the service layer, behind a seam

Not in the request pipeline. A filter-level check decides *before* the
transaction that moves money opens, leaving a window between "no prior request
found" and "funds reserved" — exactly the race the requirement is about. It
also fights the servlet API: hashing the body consumes the stream, and
replaying a `201` means re-emitting a response you didn't produce.

The seam, so the implementation can be replaced without touching money
movement:

```java
public interface IdempotentExecution {
    <T> T executeOnce(String key, String payloadHash, Supplier<T> operation);
}
```

Named replacements it holds a place for: pessimistic `SELECT ... FOR UPDATE`
instead of relying on constraint violation, a Redis-backed store once there is
more than one instance, or folding into the outbox.

> **[Ticket 16](16-idempotent-execution.md)** built the storage and the claim
> mechanics underneath this port and deliberately left the port itself to
> [ticket 17](17-duplicate-resolution.md), which is the first thing with a caller
> for it.
> **[Ticket 17](17-duplicate-resolution.md)** built the port, and it takes a
> fourth parameter the sketch above does not: a replay is read back out of a
> database column, and erasure means `T` cannot say what to read it back as.
> **[Ticket 18](18-idempotency-concurrency-tests.md)** put the alternative this
> section rejects under a test: the window a check before the transaction leaves
> open is the one two concurrent requests now walk into.

---

## 4. Request handling is three phases

1. **Claim the key** — insert the idempotency row as `IN_PROGRESS`, commit
   immediately and alone. The unique constraint serialises concurrent
   duplicates.
2. **Resolve the FX rate** — no transaction open, no locks held. A flaky
   provider costs only wall clock.
3. **Reserve funds** — one transaction: lock both accounts, check the available
   balance, reserve, create the transfer and its check ledger, *and* flip the
   idempotency record to `SUCCEEDED` with the stored response.

Phase 3 bundles the status update with the reservation deliberately. If they
could commit separately, a crash between them leaves funds reserved and the
record stuck at `IN_PROGRESS` — the client is told `409` forever for a transfer
that really happened. Bundled, the only crash window is after phase 1: a
claimed key with nothing reserved, ~~which is a stale row and exactly what the
retry path exists for~~ — a stale row that no retry can clear, because the retry
path is the `FAILED` one and a crash leaves `IN_PROGRESS`.

> **[Ticket 16](16-idempotent-execution.md)** struck that clause and has the
> evidence, along with the propagation this section implies for the `SUCCEEDED`
> flip without naming it. The unrecoverable stale row is in
> [deferred.md](../deferred.md).
> **[Ticket 17](17-duplicate-resolution.md)** put the three phases in an order,
> and ships with **phase 2 absent** rather than guessed at: `executeOnce` opens
> the phase-3 transaction, so there is nowhere in the port to run an FX call
> outside it, and ticket 26 is the first caller that has one.
> **[Ticket 18](18-idempotency-concurrency-tests.md)** is what makes phase one's
> "the unique constraint serialises concurrent duplicates" load-bearing: dropped,
> two concurrent requests both claim the key and both reserve, 20 runs out of 20.

> **Phase 3 is built in [ticket 13](13-reserve-funds.md)** and reached over HTTP in
> **[ticket 14](14-request-transfer-endpoint.md)**, whose transaction is the one ticket 16
> widens to carry the idempotency record's flip in the same commit.

---

## 5. Duplicate resolution

Resolved at the top of `executeOnce`, before the FX call, so a duplicate costs
nothing. The insert is the test; on constraint violation, read the row:

| Existing status | Response |
|---|---|
| `IN_PROGRESS` | `409 Conflict` |
| `SUCCEEDED` | replay the stored `201` |
| `FAILED` | proceed and execute |
| key reused with a different payload | `409 Conflict`, distinct error code |

Two traps:

- **The `FAILED` path is itself a race.** Two retries both read `FAILED` and
  both proceed. Claim it with a conditional update —
  `UPDATE ... SET status='IN_PROGRESS' WHERE key=? AND status='FAILED'` — and
  check the affected-row count. Exactly one caller gets `1`; the loser gets
  `409`. The same conditional-update-plus-rows-affected pattern recurs for
  verdicts and for expiry.
- **Marking `FAILED` needs its own transaction.** A rollback would undo the
  status write too, stranding the row at `IN_PROGRESS` and making retry
  impossible. Use `@Transactional(propagation = REQUIRES_NEW)`.

Keys must be well-formed UUIDs (`400` otherwise). `FAILED` covers both business
rejections and infrastructure failures — both retryable. Since the request
returns once funds are reserved, this only governs reservation-time failures,
not transfer outcomes.

The payload-mismatch case shares `409` with the in-progress case but carries a
distinct problem `type` URN (`urn:problem:request-in-progress` vs
`urn:problem:idempotency-key-reused`, see §18), because a client may retry the
first and must never retry the second.

> **[Ticket 14](14-request-transfer-endpoint.md)** makes the key required at the endpoint
> before any of this resolution exists, so no client is ever written against a version that
> let them omit it.
> **[Ticket 16](16-idempotent-execution.md)** built both traps — the conditional
> update and the new transaction — and found that the second one has a lock cost
> this section does not mention: `markFailed` has to be called *after* the failing
> transaction, not from inside it.
> **[Ticket 17](17-duplicate-resolution.md)** built this table, and settled what
> the payload hash is taken over — the *parsed* request, so that whitespace is not
> a payload difference — and why an absent row rethrows the constraint violation
> instead of being read as this key's.
> **[Ticket 18](18-idempotency-concurrency-tests.md)** reached the losing side of
> the conditional update with a real race, and found that it needs the threads held
> at the *read* of the claim rather than at a row lock — 20/20 against 8/20.
> **[Ticket 35](35-idempotency-key-module.md)** made the payload-mismatch row
> unreachable from the frontend rather than merely handled, by keying the client's
> Idempotency Key on the payload too.
> **[Ticket 40](40-transfer-form.md)** is the caller that puts a key on the wire, and
> the half ticket 35's module cannot enforce: the key is read off the *mutation's
> variables*, so a retry re-sends the payload its first attempt sent rather than
> whatever the form holds by then.

---

## 6. Concurrency: pessimistic locks in a deterministic order

`@Lock(LockModeType.PESSIMISTIC_WRITE)` on the account lookup, with accounts
always locked in **ascending ID order** rather than by their role in the
transfer. Opposing transfers then contend for the same lock first, so deadlock
is structurally impossible rather than merely unlikely.

Viable because §4 moved the FX call out of the transaction — the classic
objection to pessimistic locking is holding locks across slow work, and the
slow work was deliberately removed.

The lock is taken *before* the balance check, or the balance read can be
invalidated before the reservation. Self-transfers (A→A) are rejected with
`422` before locking.

**Rejected — optimistic locking (`@Version`).** Needs a bounded retry loop, and
retries hardest exactly when contention is worst.

**Rejected — an application-level `reserved` flag on the account.** A database
lock is released automatically when a transaction ends, including on a dropped
connection; a flag is a row you wrote, so a crash between setting and clearing
freezes the account permanently, requiring a TTL and a reaper. Setting the flag
safely takes a row lock anyway, so it layers on the engine's locking rather
than replacing it. It converts an invisible ~50ms wait into a client-visible
`409`. And it does not fix deadlock — the same ordering rule is still needed.

*A lock is infrastructure; a reservation is domain state.* If it outlives a
request and a human can see it, model it. If it only stops two concurrent
writes inside one request, that is what the database is for.

> **Built in [ticket 12](12-ordered-account-locking.md)**, which settled where
> the order lives — two locking statements rather than one ordered query — and
> what evidence there can be for it before ticket 13 has two threads.
> **[Ticket 13](13-reserve-funds.md)** has the two threads, and records what a
> test has to do before they prove anything.
> **[Ticket 14](14-request-transfer-endpoint.md)** adds the self-Transfer refusal this
> section calls for, ahead of the lock rather than inside it.
> **[Ticket 18](18-idempotency-concurrency-tests.md)** uses a row lock as a
> rendezvous rather than as a subject, to hold a claim open across a duplicate's read.

---

## 7. Transfers have an asynchronous lifecycle

`PENDING` → `SETTLED` / `REJECTED` / `EXPIRED`. Funds are reserved at request
time; the money moves at settlement.

The reasoning, what it cost, and the rejected alternatives are in
[ADR-0001](../adr/0001-asynchronous-transfer-lifecycle.md) — the one decision here
a fresh reader would otherwise "simplify" back to synchronous. Sections 8–15
below are its mechanics.

> **Built in [ticket 11](11-transfer-entity.md)**, which put the four states in a
> table and left every transition to the ticket that has a caller for it.
> **[Ticket 20](20-record-verdict.md)** is that caller for `SETTLED` and
> `REJECTED`, and records why both live behind one operation rather than beside
> the Verdict that triggers them.
> **[Ticket 40](40-transfer-form.md)** is the frontend half: submitting navigates to
> the Transfer's own page, so a `PENDING` Transfer's state lives in the URL and
> survives a refresh rather than in the tab that asked for it.

---

## 8. Orchestration, driven by a per-transfer check ledger

The transfer service owns the state machine. At creation — *in the same
transaction as the transfer*, so a transfer can never exist without its
checklist — a policy writes one row per required check:

| transfer_id | check | status |
|---|---|---|
| t-1 | `FRAUD` | `PENDING` |
| t-1 | `MANUAL_APPROVAL` | `PENDING` |

Settles when no row is `PENDING` and none is `REJECTED`; rejects the moment any
row is `REJECTED`. A new check type is a policy line and a row.

**Rejected — choreography.** With no central record of what a transfer waits
on, "why is this stuck" has no queryable answer and the frontend has nothing to
render. The ledger *is* the pending-state UI, the audit trail, and the test
seam.

Verdicts must be idempotent — a check reporting `APPROVED` twice must not
advance anything twice. The transition carries the conditional update of §5, but
that guard is not what makes concurrent Verdicts safe: what closes the race is a
pessimistic lock on the Transfer row, taken before the ledger is read. Two
Verdicts that cannot see each other's uncommitted rows both decide to wait, and
leave the Transfer `PENDING` for ever without either ever reaching an `UPDATE`.

> **Built in [ticket 19](19-check-ledger-and-policy.md)**, which records why the
> `status` column above became a nullable `verdict` — an unanswered check has no
> verdict rather than a third one — and why the decision function refuses an
> empty ledger instead of settling it.
> **[Ticket 20](20-record-verdict.md)** corrected the idempotency claim above,
> measuring that under the row lock the conditional update cannot fire, and gives
> a Verdict arriving on a terminal Transfer its own refusal because the guard
> cannot serve it.

---

## 9. Verdicts arrive by inbound HTTP callback

`POST /internal/transfers/{id}/checks/{check}`, as a thin adapter over a
`recordVerdict(transferId, check, outcome)` domain operation. Tests drive the
operation directly; a broker consumer would be a second adapter over the same
operation.

The asymmetry with outbound events is deliberate, not accidental: outbound
needs an outbox because *we* own the atomicity of "the transfer committed,
therefore the event exists." Inbound owns no such thing and only needs to be
idempotent.

Manual approval is not a service — it is a human in a back-office UI reporting
through the same operation. Automated and human approvers are indistinguishable
to the orchestrator, which is the generality the model exists for.

> **Built in [ticket 21](21-internal-verdict-endpoint.md)**, which records why
> the adapter answers `200` with a receipt rather than the `204` a thin adapter
> suggests — a Check service reporting one of two Checks would otherwise have to
> fetch the Transfer back to learn what its own Verdict did — and why the request
> body carries the Verdict alone, the Transfer and the Check being the address.

---

## 10. Internal endpoints sit behind a shared secret

Internal operations live under `/internal/**`; the `SecurityFilterChain`
requires a configured secret header on that prefix while `/api/**` stays
`permitAll`.

This is where "no auth" stops being theoretical: an unauthenticated verdict
endpoint means anyone approves their own transfers and walks past fraud
detection entirely. It also gives the Spring Security chain one real rule to
enforce, which is what makes the whole configuration legible.

Not a contradiction of §2 — that declined to model *user* identity. This is
service-to-service trust across a boundary, a different concern created by the
async model. A separate port is the production hardening step (see
[deferred.md](../deferred.md)).

> **Built in [ticket 21](21-internal-verdict-endpoint.md)**, which records why
> the rule is an `AuthorizationManager` on the chain rather than a filter, why a
> refusal is `403` and never `401` — a bespoke header is no registered
> authentication scheme, so there is no honest challenge to send — and why a
> denial now carries a §18 problem document, which is ticket 04's open question
> answered by the first refusal a caller is meant to read.

---

## 11. Check services are pushed to, via the outbox

`CheckRequested` is a second event type on machinery the task's requirement 3
(*Rendszerintegráció*) demands regardless
("továbbítása … a külvilág felé" is a push). Marginal cost: one event type.

**Rejected — check services polling us.** It looks cheaper but is not: it costs
a query API, cursor semantics so a caller tracks what it has not seen, and
claim semantics so two Fraud Detection instances do not both grab the same
transfer — all *in addition to* the outbox. It also couples more tightly to a
service we do not control, requiring it to track cursors against our API.

---

## 12. Hand-rolled transactional outbox

An event row is written in the same transaction as the transfer, so neither can
exist without the other. A `@Scheduled` poller publishes unsent rows through a
one-method `EventPublisher` interface and marks them sent. At-least-once;
consumers must tolerate duplicates, which they must anyway.

**Kafka does not replace this.** "Commit to the database, then send to Kafka"
is still two writes with no transaction spanning them. Kafka is the transport
under `publish()`; the outbox is what makes the handoff atomic.

Hand-rolled (~55 lines) rather than adopting Spring Modulith's Event
Publication Registry, because the outbox is one of the few places the task asks
for demonstrated architectural judgement and a dependency demonstrates less of
it than code you can defend. Modulith and Kafka are named in the README as the
production answers — see [deferred.md](../deferred.md).

In this build `publish()` writes a structured log line, and a profile-gated
`StubFraudDetection` consumes `CheckRequested` and reports a verdict a beat
later, so the async lifecycle is *visible* in the UI rather than taken on
faith. It can reject as well as approve, so the rejection path is demoable. It
is a fake consumer, not a fake mechanism — the outbox, ledger and verdict
endpoint it exercises are all real.

> **Built in [ticket 27](27-outbox-and-poller.md)**, which settled the three
> things this paragraph's sentence leaves open: what a caller in `transfers`
> names to write a row — a second public type that is not a second port — which
> side of a transaction each half runs on, and what stops the poller racing the
> test suite. The publish is deliberately outside a transaction and the mark
> inside one of its own, because that ordering is the only one of the three
> available that is at-least-once rather than at-most-once. §11's `CheckRequested`
> is the event type this makes possible and ticket 28 emits.

---

## 13. Balances: two fields on `Account`

`balance` (what the account holds) and `reservedAmount` (committed to in-flight
transfers). Available = `balance - reservedAmount`, which is the overdraft
check under the lock from §6. Reserving increments `reservedAmount`; settling
decrements both; rejecting or expiring decrements only `reservedAmount`.

This is the customer-facing model real banks present — "ledger balance" and
"available balance" are the industry terms, and a card authorization hold works
exactly this way.

What banks run *underneath* is double-entry: balances are a materialized
projection over a posting log, with internal accounts (suspense, clearing, FX
position) absorbing cross-currency differences so each currency's books balance
independently. Deferred deliberately — see [deferred.md](../deferred.md).

> **Built in [ticket 08](08-account-entity.md)**.
> **[Ticket 10](10-list-accounts-and-seed.md)** puts the derived Available Balance
> on the wire, and records why the endpoint that reports it cannot be tested with
> a mocked service.
> **[Ticket 13](13-reserve-funds.md)** adds the increment, and records why the
> overdraft refusal lives on `Account` rather than in the service that holds the
> lock.

---

## 14. Unanswered checks expire

Each check row carries a deadline. A `@Scheduled` reaper finds overdue
`PENDING` transfers, marks them `EXPIRED`, releases the reservation and
publishes the event.

Without it, a check that never responds leaves funds reserved forever with no
transaction to show for it — and `PENDING` would have no exit that does not
depend on an external service behaving. Cheap because the scheduled-poller
machinery already exists for the outbox.

A verdict arriving *after* expiry must not settle the transfer. Same
conditional-update guard as §5.

---

## 15. The FX rate is locked at request time

Fetched during creation, stored on the transfer with its fetch timestamp, used
at settlement.

The argument is failure handling, not accuracy. Fetching at settlement means
the flaky provider is called *after* every check has approved — a 503 there
leaves an approved transfer that cannot settle, with no caller to tell and no
one to retry, requiring a settlement retry queue and a policy for permanently
unsettleable transfers. Locking at request keeps the failure in front of the
caller, which is the "handle the flaky API elegantly" requirement, and keeps
the call in the position §4 put it.

Rate staleness on a long-pending transfer is already handled: **the quote's
validity window and the check deadline in §14 are the same clock.** A transfer
that outlives its quote expires and releases its funds. Whether to re-quote
instead is a business decision, not a technical one.

---

## 16. Money is a `long` count of minor units

`record Money(long minorUnits, Currency currency)`, as a JPA `@Embeddable` —
one record flattens to two columns. Amounts are counted in fillér and cents,
never in forints and euros.

**Rejected — `BigDecimal`.** The usual argument against it (float drift) does
not apply: `BigDecimal` is exact. The real problem is that it is
**unconstrained**. Nothing stops a mis-scaled column or a careless division
producing `100.505` HUF — a quantity that cannot exist. `long` fillér makes the
sub-unit *unrepresentable* rather than merely discouraged, and it leaves
exactly one place in the codebase where rounding happens.

It also removes a trap with no TypeScript equivalent: **`BigDecimal.equals`
compares scale**, so `new BigDecimal("1.0").equals(new BigDecimal("1.00"))` is
`false` while `compareTo` says they are equal. Two amounts that are the same
money fail an equality assertion, and a `record`'s generated `equals` inherits
the problem.

- **Wire format is an integer count of minor units, in a field named
  `amountMinor`.** The name is the mitigation: `"amount": 10050` invites
  someone to read 10050 forints, `"amountMinor": 10050` does not.
- **Per-currency decimals (EUR/USD 2, HUF 0) live only at the edges** — form
  parsing and display. The core never divides by 100.
- **The rate stays `BigDecimal`.** It is the one value that is genuinely a
  decimal, and it is never money.
- `RoundingMode.HALF_EVEN`, and **`422` when a converted amount rounds to
  zero** — 1 HUF to EUR would otherwise debit the source and credit nothing.

The single conversion, and the most error-prone line in the backend:

```
destMinor = round(srcMinor × rate × 10^(destScale − srcScale))
```

e.g. `10050 × 390.12 × 10^(0−2) = 39207.06 → 39207 Ft`. Table-test it: EUR→HUF,
HUF→EUR, USD→EUR, same-currency (no provider call at all), and round-to-zero.

**Honest consequence: money is not conserved across the two accounts.** The
rounding remainder vanishes. That is what the double-entry deferral costs — see
[deferred.md](../deferred.md).

**Verify in the first hour:** Hibernate's support for `record` types as
`@Embeddable`. It has been supported since Hibernate 6.2 and Boot 4 ships
Hibernate 7, but a wrong assumption here re-shapes every entity.

> **Confirmed in [ticket 01](01-project-skeleton.md)** and **built in
> [ticket 06](06-money-and-currency.md)**, with a surface deliberately smaller
> than the type could support. The conversion above is
> **[ticket 07](07-conversion-function.md)**, which settled what it returns when
> the result rounds to zero.
> **[Ticket 09](09-create-account-endpoint.md)** puts the first such field on the
> wire and changes the suffix above: `…MinorUnits`, not `…Minor`.
> The frontend edge this section reserves — form parsing and display — is
> **[ticket 33](33-money-format-module.md)**, which found that the invertibility a
> form field needs rules out `Intl`'s currency formatting, and that a `long` count
> has a ceiling in the browser that it does not have in the JVM.

---

## 17. One event stream, thin events, no catch-up

`GET /api/events/stream` — a single SSE endpoint, not one per transfer.
(SSE over WebSocket: the push described in the task's requirement 3 is
one-directional, and SSE reconnects on its own.)

**Liveness is scoped to the transfer submit flow only** — the `/transfers/:id`
screen. Accounts and Transactions refetch on navigation and on window focus.
Watching a balance tick live on a list nobody is looking at is not a
requirement.

Given that scope, a single endpoint wins for a small reason rather than a grand
one: one emitter registry plus a client-side `if (e.transferId !== mine) return`
is less machinery than a registry keyed by transfer ID with subscription
lifecycle and cleanup.

**No catch-up: no `Last-Event-ID`, no replay buffer, no outbox-as-event-log.**
The client refetches `GET /api/transfers/{id}` when the stream opens.

> The stream carries hints; the REST endpoint carries truth.

That converges from any missed state — a dropped connection, a server restart,
an event published before the browser subscribed — and it reuses the first-load
path rather than adding a second one. Transfer status is monotonic, so
subscribe-then-fetch needs no ordering logic: a stale fetch can only be
overtaken by a later invalidation.

**Event fatness is deliberately asymmetric**, and it looks like an
inconsistency unless it is stated:

| Direction | Shape | Why |
|---|---|---|
| SSE → browser | thin: `{ type, transferId }` | the browser can call our API |
| Outbox → services | fat: carries the payload | a consumer that must call back for the amount is exactly the coupling §11 rejected polling to avoid |

One named consequence of the narrow scope: after a terminal event the client
refetches accounts, so a balance the user just watched change is not stale when
they navigate away.

> The browser's half is **[ticket 36](36-sse-event-module.md)**, built before the
> endpoint that feeds it: it names the three event types the stream must emit
> (`TRANSFER_SETTLED`, `TRANSFER_REJECTED`, `TRANSFER_EXPIRED`), on the default
> event as JSON rather than as named SSE events, and it invalidates the accounts
> list on all three — every terminal state releases the reservation, not only
> settlement.
> **[Ticket 38](38-accounts-list-screen.md)** builds the first of the two screens this
> scope leaves outside liveness, and turns the compensation named above into a declared
> property rather than an inherited default — with the absence of a `staleTime` as the
> half that actually carries it.

---

## 18. Errors are RFC 9457 `ProblemDetail`

Responses are `application/problem+json`. **The `type` URN is the sole
discriminator** — not `type` *and* a `code` field, because two discriminators
drift and eventually one of them lies.

*(This section was written expecting `spring.mvc.problemdetails.enabled=true` to
be the switch. It is not the one this build uses — see
[ticket 05](05-problem-detail-contract.md).)*

**Rejected — a hand-rolled error envelope.** Spring already emits
`ProblemDetail` for framework-level failures: `@Valid` rejections, `415`,
`405`, malformed JSON. Those responses are produced before any controller code
runs, so a custom envelope does not replace them — it ships a **second** error
shape alongside one you cannot switch off.

Attached to this:

- **`Retry-After` on the in-progress `409`, absent on the key-reuse `409`.**
  That gives [deferred.md](../deferred.md)'s retry-semantics entry something
  concrete: the retryable case is machine-readably marked as such.
- **Field-level validation errors as an extension member.** Spring's default
  packs every violation into one unusable sentence; the form needs them per
  field.
- The client branches on `!response.ok` and parses one shape. Nothing else.

This supersedes §5's mention of a separate `code` field: the two `409` cases are
distinguished by their `type` URN (`urn:problem:request-in-progress` vs
`urn:problem:idempotency-key-reused`).

> **Built in [ticket 05](05-problem-detail-contract.md)**, with five things the
> ticket did not ask for and that the implementation needed anyway.
> **[Ticket 17](17-duplicate-resolution.md)** is where the two `409`s stop being
> a fixture and become real, and it chooses the value this section leaves open:
> `Retry-After: 1`.
> **[Ticket 09](09-create-account-endpoint.md)** widens that ticket's rule: a
> body Jackson rejected *at a member* is a validation failure with an `errors`
> entry, not `urn:problem:malformed-request`.
> **[Ticket 14](14-request-transfer-endpoint.md)** adds the first slice's own refusals —
> four `422` URNs — and settles where a slice's exception-to-status mapping lives.
> **[Ticket 32](32-openapi-type-generation.md)** found that none of this reaches
> `/v3/api-docs` unaided — the advice produces it, so springdoc has nothing to
> introspect — and publishes the URNs and the document's shape from the enum.
> **[Ticket 34](34-problem-document-module.md)** builds the client half the rule
> was written for, and keeps it from eroding: the frontend's mapping may not name
> a status code, asserted against its own source.
> **[Ticket 21](21-internal-verdict-endpoint.md)** carries the contract past the
> last response that was exempt from it — a refusal raised in front of the
> dispatcher, which no `@ControllerAdvice` can see — and is where `instance` is
> set by hand for the first and only time.
> **[Ticket 40](40-transfer-form.md)** declines the extension that would have grown the
> second discriminator by another route: `insufficient-funds` carries an
> `availableBalanceMinorUnits` worth rendering, and a reader that knew *which URNs carry
> extra members worth rendering* is a URN branch beside the one this section allows.

---

## 19. The Transactions list shows every status

One list with a status column and an optional `?status=` filter — not a list of
completed transfers only.

The spec's *"végrehajtott tranzakciók"* was written against a synchronous model,
where every transfer is either done or never happened. Under §7 the faithful
reading is "requested". §12 built a stub consumer specifically to make the
lifecycle *visible*, and §8 says the check ledger **is** the pending-state UI —
filtering pending transfers out of the only list screen throws both away.

Consequence for the vocabulary: `Transaction` stops being an entity. One list
showing every status means a second word for "a transfer that has been
recorded" earns nothing, so it survives only as the screen's label. See
[CONTEXT.md](../../CONTEXT.md).

> **[Ticket 15](15-list-transfers-endpoints.md)** builds both endpoints and turns
> the reading above into an assertion: every status is listed, `?status=` narrows
> to one, and `?status=` with no value is no filter rather than a rejected one.
> The order is `created_at DESC, id DESC` — the tie break is part of the contract,
> because without a total order the screen reshuffles itself between two refetches
> of unchanged data.

---

## 20. No meta-framework: Vite + React, two processes

**Rejected — Next.js.** There is no SSR or SEO requirement (this is an
authenticated-shaped internal tool), and React Server Components actively fight
this app: the data lives behind a Java API, the transfer screen holds a live SSE
connection, and the form is stateful — every interesting component would be
`"use client"`. Decisively, it adds a **second production runtime** beside
Spring for a build that already has one.

The build tool then falls out: Vite. (CRA is deprecated.)

**Two processes, documented rather than papered over:** `./mvnw spring-boot:run`
and `npm run dev`, with Vite proxying `/api` and `/internal`. Improving the
developer experience is a stretch goal, not a requirement; single-jar packaging
goes to [deferred.md](../deferred.md) *together with* the SPA-fallback trap it
creates, so that trap is not rediscovered from scratch.

**Trap now:** the Vite dev proxy must not buffer the SSE stream. If it does,
events arrive in a clump when the connection closes and the live UI appears
broken in development only.

README framing: the strongest case for Next.js is that reaching for it is the
reflex. That makes declining it a better answer to *"mit vetettél el és
miért"* than adopting it would have been.

> **Built in [ticket 31](31-frontend-toolchain.md)**, which measured the trap:
> the buffering is response compression, and the proxy hop asks for `identity`.

---

## 21. TanStack Router, and TanStack Query owns server state

**Router: TanStack Router**, chosen over React Router for typed route
configuration — a deliberate trade against familiarity and ecosystem size, not
a claim that it is the better library in general.

| Route | Screen |
|---|---|
| `/accounts` | list + create |
| `/transfers/new` | submit form |
| `/transfers/:id` | live status — the only SSE consumer |
| `/transfers` | the Transactions list |

Submitting navigates to `/transfers/:id`, so **pending state lives in the URL
rather than in transient component state** and survives a refresh. That is the
frontend half of §7.

Notes: TanStack Router needs `"strict": true` in `tsconfig.json` or its
inference silently degrades; decide up front whether `routeTree.gen.ts` is
committed, because a half-committed generated file is a confusing diff.

**Query: TanStack Query.** The payoff of §17's thin events is that an SSE
message becomes nothing more than a **cache invalidation**:

```ts
queryClient.invalidateQueries({ queryKey: ['transfers', e.transferId] })
queryClient.invalidateQueries({ queryKey: ['accounts'] })
```

No merging, no reconciliation, no ordering logic. `refetchOnWindowFocus` (on by
default) quietly closes the scope hole §17 left on the other two screens.

Retry policy must be narrowed: `retry: (n, err) => n < 3 && err.status >= 500`.
Query's default retries anything — retrying a `422` is noise, and a `409
REQUEST_IN_PROGRESS` should wait for its `Retry-After` (§18), not Query's
backoff.

> **Both built in [ticket 31](31-frontend-toolchain.md)**: the route tree is
> committed *and* regenerated by the build, and the retry rule is a tested
> function rather than a lambda in the config — which narrowed the attempt
> count from this section's four to three.
> **[Ticket 36](36-sse-event-module.md) corrects the query keys sketched above**:
> `['transfers', id]` makes the Transactions list the prefix of every Transfer page,
> so invalidating the list refetches all of them. The list and the detail are
> separate branches, and the keys live in `queryKeys.ts` rather than inline.
> **[Ticket 38](38-accounts-list-screen.md)** is the first screen to read through the
> client, filing its query under `queryKeys.accounts()` so a stream event's invalidation
> reaches it, and writing `refetchOnWindowFocus` out rather than leaving the parenthesis
> above to a library default an upgrade could change.
> **[Ticket 40](40-transfer-form.md)** is the first screen to need a mutation *semantic*
> rather than its state: `mutationFn` is re-invoked with the variables `mutate` was
> called with, which is the whole of why a retry cannot mint a second Idempotency Key.
> It also owns the second reader of `queryKeys.accounts()`, and invalidates nothing on
> success — there is no `staleTime`, so the screen it navigates to refetches anyway.

---

## 22. TanStack Form + zod, and no global state library

**No Redux, Zustand or Jotai.** Once Query owns server state and the router owns
route state, there is no client state left to manage. The README answer is *"I
didn't add one, because there was nothing left to manage"*; if one were ever
needed, the escalation is Context + `useReducer` before a library.

**Form: TanStack Form** (over react-hook-form — provider consistency), with zod
for the schema. zod is still needed, and how the two connect has changed:

- TanStack Form has **no schema language of its own**; as of v1 it speaks
  **Standard Schema** natively. `@tanstack/zod-form-adapter` and `zodValidator`
  are **gone** — pass the schema straight in as
  `validators: { onChange: schema }`. Requires zod ≥ 3.24.
- **Transforms do not flow through.** The docs are explicit: *"Validation will
  not provide you with transformed values."* `onSubmit` receives **input**-typed
  data, so getting the output type means calling `schema.parse(value)` inside
  `onSubmit`. The property worth keeping — the schema owns the
  decimal → minor-unit conversion — survives as one explicit line.
- `z.input` vs `z.output` is the confusing part (`z.infer` aliases *output*).
  Worth a comment where it bites.
- **Cross-field rules go in the form-level validator**, which can set errors on
  several fields at once. The self-transfer check lives there, caught in the
  browser before §6's `422`.
- Known friction: a form-level zod `onChange` validator over-renders
  (TanStack/form#1625). Irrelevant at four fields.

**Currency is not a form field.** The amount is *always* denominated in the
source account's currency, so the payload is
`{ fromAccountId, toAccountId, amountMinor }` plus `X-Idempotency-Key`, and the
server derives the currency from the source account. That deletes a whole bug
class — a client claiming EUR on a HUF account cannot be expressed.

The zod schema is therefore a **factory over the accounts list**,
`makeTransferSchema(accounts)`, so the decimal-scale rule derives from the
selected source account. Because the form-level validator re-runs on any field
change, switching the source account from EUR to HUF immediately re-invalidates
`"100.50"` with no manual dependency wiring. The UI shows the currency as an
adornment on the input.

Sources: `tanstack.com/form/latest/docs/framework/react/guides/validation` and
`.../submission-handling`.

> **The server side of that payload is [ticket 14](14-request-transfer-endpoint.md)**, which
> keeps `fromAccountId` / `toAccountId` on the wire against the domain's own *source* and
> *destination*, and spells the amount `amountMinorUnits`.
> **[Ticket 39](39-create-account-form.md)** is the first form, and the transform finding
> above cost it exactly the one line predicted. It needs no factory — its Currency is a
> field — and it found that registering the schema under `onSubmit` *as well as* `onChange`
> prints every message twice, because the change validator already runs on submit.
> **[Ticket 40](40-transfer-form.md)** is the form this section was written about, and
> every clause of it held: the factory (spelled `transferSchemaFor`), the adornment as the
> *only* place a Currency appears, the self-Transfer rule in the form-level validator, and
> the re-invalidation of an untouched `100.50` when the source Account moves. What the
> section does not say is which field a cross-field sentence lands under, and that is the
> destination — the side an operator changes.

---

## 23. Bootstrap 5, CSS only, plus CSS Modules

**Rejected — shadcn/ui, MUI, Mantine** (overkill for four screens) **and
no library at all** (underkill — hand-rolled CSS at this size looks like a
corner cut).

The specific reason a component library's value is unused here: **this app has
no JavaScript-driven components.** There is no dialog, combobox, popover or
dropdown menu. A `<select>`, a form, a table, a badge and a spinner are native
elements or pure CSS. Radix's entire proposition — managed focus, roving
tabindex, portal rendering — buys nothing on this screen list, which is also the
README justification.

- **No `react-bootstrap` and no Bootstrap JS bundle** — a single CSS import.
  The reflex is to reach for the React wrapper, which reintroduces exactly the
  weight this choice avoids.
- CSS Modules need no Vite configuration.
- `.is-invalid` / `.invalid-feedback` map directly onto TanStack Form's error
  state.
- The dated look is about ten lines of Bootstrap 5.3 CSS custom properties
  (`--bs-primary` and friends); `data-bs-theme="dark"` is built in.

> **Wired in [ticket 31](31-frontend-toolchain.md)**: one CSS import, no JS
> bundle in the output, and `noUncheckedIndexedAccess` rejected because it
> types every CSS Module class as possibly `undefined`.
> **[Ticket 38](38-accounts-list-screen.md)** spends the table from the vocabulary above
> and rejects a card per Account — five aligned figures a row is what a table is for —
> and opens the second CSS Module for the one rule that keeps the digits lined up.
> **[Ticket 39](39-create-account-form.md)** spends the `<select>` and the form, and finds
> that `.is-invalid` / `.invalid-feedback` do map onto the error state as promised — with
> one wrinkle, that a feedback element inside an `.input-group` needs `has-validation` on
> the group to show at all.
> **[Ticket 40](40-transfer-form.md)** builds the second screen out of the same vocabulary
> and needs no addition to it: two `<select>`s, an `.input-group` adornment and an alert,
> with no CSS Module of its own.

---

## 24. Frontend testing: extract logic, unit test it, Playwright end-to-mock

The rule: **pull logic out into pure TypeScript modules and unit test those;
write as few React integration tests as possible.** Integration confidence comes
from the browser, not from jsdom.

Extraction list — `money.ts`, `transferSchema.ts`, `problem.ts`,
`idempotency.ts`, `events.ts`. Applying the rule turns the three tests that
matter most into unit tests:

- **Idempotency key lifecycle** → a plain module, no React.
- **`ProblemDetail` handling** → `problemToMessage(problem): { title, body,
  retryable }`, a pure table test.
- **SSE handling** → `invalidationsFor(event): QueryKey[]` is pure; React only
  does `keys.forEach(k => qc.invalidateQueries({ queryKey: k }))`. **This makes
  the SSE yak-shave disappear**: no jsdom `EventSource` polyfill, no MSW
  streaming, no flake.

Stack: **Vitest + React Testing Library**. Vitest over Jest because Vite is
already there — same config, same transform pipeline, no second toolchain. **MSW
probably drops entirely**: Playwright owns network mocking, and the surviving
RTL tests ("does this field show its error") touch no network.

**The idempotency-key rule, which is the frontend half of a graded
requirement:** the key identifies a **user intent**, not an HTTP attempt.
Generate it once when the form becomes ready, hold it in a ref, and ~~reset it
only after a success~~ — reset it on a success *and* on a changed payload,
because a payload the server did not first see is refused under the key it was
first given, so correcting a rejected amount under the held key can never
succeed. Generating it inside `mutationFn` defeats the entire
mechanism — every retry gets a fresh key and the server sees a new transfer.

**Playwright is end-to-mock, not true E2E.** A real browser — real
`EventSource`, real focus and blur, real navigation — against a deterministic
scripted backend. Mock with **`page.route()`, not MSW-in-browser**: no service
worker, no extra build mode, and the mocks live in the spec that depends on them.

**`route.fulfill()` takes `string | Buffer` only — there is no streaming body**
(Playwright #33564 is the open request; #15353 is the SSE question). So an SSE
event cannot be pushed mid-test, which is exactly what asserting
`PENDING → SETTLED` needs. Instead **stub `window.EventSource` via
`addInitScript`**, which runs before app code:

1. assert the row reads Pending
2. `page.route` the transfer endpoint to return the settled version — *truth
   changes*
3. `page.evaluate` dispatches a `MessageEvent` on the fake source
4. assert the row reads Settled

That drives §17's design end to end — thin event → invalidate → refetch →
render — deterministically, with no `waitForTimeout`. True E2E goes to
[deferred.md](../deferred.md) with its gap named.

> **Vitest arrived in [ticket 31](31-frontend-toolchain.md)**, ahead of the
> tickets that assume it, and needs no configuration: every module on the
> extraction list runs in the default Node environment.
> **[Ticket 33](33-money-format-module.md)** built the first module on that list,
> and made the rule an assertion rather than a convention: `money.test.ts` reads
> its own subject's source and pins its import list.
> **[Ticket 34](34-problem-document-module.md)** built the second, keeping this
> section's signature, and found that its argument has to be `unknown` — a typed
> parameter would push a cast onto every caller.
> **[Ticket 35](35-idempotency-key-module.md)** built the third, struck that clause
> and has the evidence, and extracted the source-pinning block ticket 33 introduced,
> now that three modules assert it.
> **[Ticket 36](36-sse-event-module.md)** built the fourth and changed this section's
> signature: `invalidationsFor` takes the frame rather than a parsed message, because
> a stream's first failure is the parse and a message handler is the worst place to
> catch it.
> **[Ticket 37](37-playwright-harness.md)** built the browser half, and holds the
> scripted answers in one mutable table behind one route handler rather than
> re-registering a route per answer as step 2 above reads. It also names the glob that
> swallows the app's own `src/api/` modules, reproduced rather than reasoned about.
> **[Ticket 38](38-accounts-list-screen.md)** is the first screen to carry its own spec,
> and found that "real focus and blur" is not free in a headless browser: Chromium removed
> `Emulation.setPageVisibilityState`, so the harness redefines `document.visibilityState`
> and dispatches `visibilitychange` on `window`, in that order.
> **[Ticket 39](39-create-account-form.md)** adds `accountSchema.ts` to the extraction
> list above — the transfer schema's sibling, arriving first — and gives the harness the
> two calls a form needs: an answer for a `POST`, and the body the browser actually sent,
> which is the only place a decimal → Minor Unit conversion can be caught getting it wrong.
> **[Ticket 40](40-transfer-form.md)** adds `transferSchema.ts` — the sibling this section
> names — with `formRefusal.ts` split out of its predecessor once there were two callers,
> and gives the harness `headersSent`, for the same shape of reason: **nothing on that
> screen renders the Idempotency Key**, so a form minting a fresh one per attempt would
> pass every assertion a DOM can carry.

---

## 25. Backend tests run on H2

**Rejected — Testcontainers.** Test what you ship: the task blesses H2 and the
app runs on H2. And Testcontainers needs Docker running on the reviewer's
machine — `./mvnw test` failing for that reason is a bad first impression bought
very cheaply. Since Playwright is end-to-mock (§24), nothing outside the JVM
needs a realistic database either.

Setup cost is one test-scoped dependency (`com.h2database:h2`); Spring Boot
auto-configures an in-memory database from there. No Docker, no container, no
port.

**The Spring testing landscape**, because which annotation you reach for *is*
the design decision:

| Layer | What Spring starts | DB? | ~boot |
|---|---|---|---|
| plain JUnit | nothing | no | 0 ms |
| `@WebMvcTest` | controllers, JSON, validation, exception handlers, security | no | 1–2 s |
| `@DataJpaTest` | JPA + in-memory DB, no web | yes | ~2 s |
| `@SpringBootTest` | everything | yes | 3–8 s |

- "Contract test the API interface" is `@WebMvcTest`, and §26 pins that contract
  from both ends.
- "Unit test the logic" is really a **design** instruction: `decide(List<CheckRow>)
  → Settle | Reject | Wait` is testable; the same logic inside a `@Service` that
  also does I/O is not. Same move as §24's extraction list.
- **What unit tests cannot reach is where this project's difficulty lives.**
  "The unique constraint serialises concurrent duplicates" (§4),
  "ascending-ID locking makes deadlock structurally impossible" (§6),
  "`UPDATE … WHERE status='FAILED'` returns 1 for exactly one caller" (§5) are
  claims about the *database*, as is every transaction boundary in the async
  lifecycle. *Konkurencia és adatintegritás* is a named graded requirement, so
  these are the tests that count.

**Three traps:**

- **Context caching.** `@SpringBootTest` boots the application context once and
  reuses it — but every distinct combination of `@MockitoBean`,
  `@ActiveProfiles` and `@TestPropertySource` creates a *separate* cached
  context. Careless variation turns one five-second boot into eight.
- **`@Transactional` on a concurrency test method silently defeats it.** Spring
  runs the test in a single transaction, so the second thread cannot see the
  first thread's uncommitted claim; both "succeed" and the test passes while
  proving nothing. Write it non-transactional with manual cleanup.
- **Line the threads up** on `Executors.newVirtualThreadPerTaskExecutor()`.
  Without anything, thread one finishes before thread two starts and the race
  never happens — but ~~with a `CountDownLatch`~~ a latch at the *start* lines up
  two beginnings and nothing else, and a whole transaction here runs in less time
  than it takes to schedule the second thread. Hold the threads at the statement
  they contend over instead. Assert **exactly** one `201` and one `409`.

Realistic suite, 15–20 tests:

- **unit** — money conversion, reject-on-zero, check policy, ledger decision
- **`@WebMvcTest`** — status codes, `ProblemDetail` URNs, missing and malformed
  idempotency keys, validation bodies
- **`@DataJpaTest`** — the idempotency unique constraint, conditional-update
  rows-affected, the pessimistic-lock query
- **`@SpringBootTest`** — concurrent same-key requests yield exactly one `201`;
  opposing transfers do not deadlock; full lifecycle create → outbox → verdict →
  `SETTLED`; FX provider fails twice then succeeds

H2's behaviour is close enough for these to be meaningful, but not identical to
Postgres — see [deferred.md](../deferred.md).

> **[Ticket 01](01-project-skeleton.md) found that Boot 4 moved the furniture**
> under all of this, and that test sources component-scan into every
> `@SpringBootTest` in the suite.
> **[Ticket 10](10-list-accounts-and-seed.md)** found the matching hazard in the
> narrow slices: two of them had narrowed one kind of scanning and not the other,
> and only broke once the application had a controller and a repository to find.
> **[Ticket 18](18-idempotency-concurrency-tests.md)** struck the latch out of the
> third trap and has the measurement, and is the first of the four
> `@SpringBootTest` tests the suite sketch above names.

---

## 26. Frontend API types are generated from OpenAPI

`springdoc-openapi` exposes `/v3/api-docs`; `openapi-typescript` generates a
`.d.ts` the frontend imports.

This is the only thing standing between end-to-mock tests (§24) and
self-congratulation: **it converts mock drift into a compile error.** A
Playwright mock returning a shape the backend no longer produces stops being a
green test and starts being a failed build. It also puts §18's problem-type URNs
in exactly one place.

Caveats: the generated file is checked in and regenerated by hand, so it needs a
CI check or at minimum a README line — otherwise it goes stale, which is its own
[deferred.md](../deferred.md) entry. **Verify springdoc's Spring Boot 4 support in
the first hour**, alongside §16's Hibernate question. Both are "has the
ecosystem caught up to Boot 4" bets: cheap to check now, expensive to discover
late.

> **Confirmed in [ticket 01](01-project-skeleton.md)**, on springdoc's 3.x line
> — 2.x targets Boot 3 and does not work here.
>
> **Built in [ticket 32](32-openapi-type-generation.md)**, which found that the
> document springdoc publishes unaided describes neither the errors nor which
> response members are always sent, and closed both on the backend first.
>
> **Regenerated in [ticket 37](37-playwright-harness.md)**, which is where "it goes
> stale" stopped being a caveat and became four compile errors — the exhaustiveness
> working, and priced.

---

## 27. The flaky FX provider: timeouts, retry, then fail to the caller

No cache, no circuit breaker. Timeouts on the `RestClient`, `@Retryable` around
the call, and on exhaustion a `503` with
`urn:problem:fx-provider-unavailable` and a `Retry-After`.

`@Retryable` is **built into Spring Framework 7**, so Boot 4 needs neither
`spring-retry` nor Resilience4j. Two migration details that every tutorial
predates: the enabling annotation is **`@EnableResilientMethods`** (not
`@EnableRetry`), and `maxAttempts` was renamed **`maxRetries`**.

- **Timeouts are non-negotiable.** Without them "responds slowly" is unbounded,
  and a virtual thread parks forever while holding an `IN_PROGRESS` idempotency
  claim — the flaky provider becomes a stuck transfer.
- Retry `5xx` and timeouts. **Never retry `4xx`.**

**Why the lean version is complete, rather than a corner cut:** §5 marks the
idempotency record `FAILED`, which is explicitly retryable, so the client
resubmits with the *same key* and the server executes properly. Failing to the
caller is not giving up — it hands the retry to the layer that already has
idempotency making it safe. §5, §15 and §21's retry policy cohere into one
answer to the "handle the flaky API elegantly" requirement.

**Rejected — a circuit breaker.** A breaker exists to protect a scarce thread
pool from being consumed by calls that will fail. Virtual threads mean threads
are not scarce, and the retry budget is already bounded by the timeout. Goes to
[deferred.md](../deferred.md).

**Rejected — caching rates.** A legitimate want, declined on scope. If it comes
back: §15 already stores the fetch timestamp on the transfer, so a cached rate
stays auditable.

**Free win:** Spring Framework 7 publishes `MethodRetryEvent`, observable with
`@EventListener`. A test can **assert that the retry actually happened** without
mocking framework internals — resilience tested rather than claimed, on a graded
line.

Sources: `danvega.dev/blog/spring-boot-4-native-retry-support`; the `@Retryable`
javadoc; the Moderne spring-retry → Framework 7 migration recipe.

> **Built in [ticket 25](25-exchange-rate-client.md)**, which records why Framework 7's
> missing `@Recover` forces the client into two beans, why neither retry setting can hold
> its default on the `@ConfigurationProperties` record, and how the exhaustion failure is
> kept distinct from a refusal that will never succeed.

---

## 28. The mock FX provider is a real HTTP endpoint inside the app

`@Profile("mock-fx")`, e.g. `GET /mock/fx/rates?base=EUR&quote=HUF`, called over
real HTTP by the real client. The client reads `payments.fx.base-url` from
configuration, so swapping in a real provider is a property change.

**Rejected — a stub `@Bean` implementing the provider interface.** It puts the
failure simulation *above* the HTTP client, so none of §27's timeouts, retries
or error mapping are ever exercised. The thing being demonstrated would be the
thing being mocked out.

**Rejected — WireMock as a separate server.** Friction for the reviewer, for a
capability the in-process endpoint already provides. In tests, Spring's
**`MockRestServiceServer`** covers it with no new dependency — it binds to
`RestClient.builder()` and scripts `503, 503, 200` cleanly. It sits *above* the
transport, though, so it **cannot exercise the read timeout** →
[deferred.md](../deferred.md).

**It must stay out of the application's cross-cutting layers**, or it stops
being a believable third party. Five distinct Spring mechanisms, each doing one
job:

- **Security: total bypass, not `permitAll`** — a `WebSecurityCustomizer` with
  `web.ignoring().requestMatchers("/mock/**")`. Spring's docs discourage
  `ignoring()` for real endpoints because it forgoes security headers; here that
  is exactly the point.
- **`@ExceptionHandler` methods on the mock controller itself**, rather than the
  application's global advice — otherwise a "third-party service" emits *our*
  `ProblemDetail` shape and the fiction collapses. A handler declared on a
  controller is resolved ahead of every `@ControllerAdvice`, so nothing has to
  be ordered and the global advice stays global.
- **`FilterRegistrationBean` with `addUrlPatterns("/api/*", "/internal/*")`** so
  logging and MDC filters never touch it.
- Out of the `CorsConfigurationSource` mapping entirely — it is
  server-to-server.
- Its own package, `…payments.mockfx`, depending on nothing in the domain (§30).

**Trap this creates:** the application now calls itself over HTTP. On Tomcat's
classic thread pool that can deadlock under load — the inbound request holds a
thread while waiting for a second one to serve its own outbound call. **This
makes `spring.threads.virtual.enabled=true` load-bearing rather than a
nicety**, and it is worth one README sentence saying why.

> **Built in [ticket 24](24-mock-fx-provider.md)**, which corrects the scoped-advice
> bullet above — it named a `payments.api` package §30's layout never creates — adds
> the published OpenAPI document as a sixth layer to exclude, and records why the
> filter bullet had nothing to register.

> The base-URL sentence above is a correction from **[ticket 25](25-exchange-rate-client.md)**:
> it named `fx.base-url`, and every property in this application is under `payments.`.
> That ticket also narrows "swapping in a real provider is a property change" — true of a
> provider that speaks this wire shape, while one that speaks another is a second
> implementation of the port — sharpens the `MockRestServiceServer` limitation named above
> — the builder *replaces* the request factory, so the timeouts are absent from that seam
> rather than merely bypassed — and records why WireMock still was not re-added.

---

## 29. Maven, Flyway, and `ddl-auto=validate`

**Maven**, the Initializr default. `./mvnw` means the reviewer installs nothing
and `pom.xml` is readable by any Java developer; Gradle's incremental-build edge
is irrelevant at this size, and §20's two-process setup means there is no
frontend plugin, no Node download and no build profile to justify it.

**Flyway with `spring.jpa.hibernate.ddl-auto=validate`**, not `create-drop`.
Partly signalling — `ddl-auto` generating schema anywhere but a throwaway
database is a known foot-gun — but the substantive reason is that the migration
directory shows every table, type, constraint and index as SQL, instead of a
schema reconstructed by reading annotations across six entity classes. It is
also the natural home for the indexes the design implies: the outbox poller's
`WHERE sent_at IS NULL`, the reaper's
`WHERE status = 'PENDING' AND deadline < ?`.

**One migration per slice, not one file up front** (amended when the work was
broken into tickets). The original decision was a single `V1__init.sql` carrying
the whole schema from the first commit, on the grounds that one file reads
better than six. That would have meant writing the outbox, check-ledger and
idempotency tables before those entities existed — schema written against a
design rather than against code, and the one artefact that can no longer be
checked by `validate` while it is being written. Each slice now ships the
migration for the table it introduces (`V1__accounts.sql`,
`V2__transfers.sql`, …), including the later ones that only add a column. What
made the original decision worth keeping — `validate` on from the very first
commit — is unaffected, and is the part that actually catches mismatches.

**From day one, not baselined at the end.** Hibernate's implicit naming strategy
silently maps `fromAccountId` to `from_account_id`. With `validate` on from the
first commit, each mismatch fails at startup naming the exact column; discovered
at the end, they arrive all at once.

**Seed data is not schema.** Demo accounts belong in a `@Profile("dev")`
`CommandLineRunner`, never in a migration — a Flyway seed runs in the test
suite too.

> This is the section the build has argued with most.
> **[Ticket 01](01-project-skeleton.md)** found what column type
> `@Enumerated(STRING)` actually emits; **[ticket 02](02-flyway-wiring.md)**
> found that the obvious Flyway dependency does not run migrations at all;
> **[ticket 06](06-money-and-currency.md)** measured the mapping and corrected
> two things this section assumed about it. Both were false sentences and have
> been struck from the text above rather than left to mislead; ticket 06's file
> records what they said and what replaced them.
> **[Ticket 08](08-account-entity.md)** wrote the first migration against all of
> it, and settled the question ticket 06 left open about `@AttributeOverride`.
> **[Ticket 10](10-list-accounts-and-seed.md)** built the `@Profile("dev")` runner
> the last paragraph asks for, and both halves of the assertion that holds it there.
> **[Ticket 11](11-transfer-entity.md)** found that `in (…)` in a `check`
> constraint is broken on H2 in a way every startup gate passes.
> **[Ticket 16](16-idempotent-execution.md)** wrote the third migration under
> ticket 11's finding, and restated `validate` inside its own test because the
> migration is half of every assertion there.

---

## 30. Package-by-feature

```
hu.bankmonitor.payments
├── accounts/      Account, AccountController · repository package-private
├── transfers/     Transfer, TransferController, TransferService
│   └── checks/    CheckLedger, CheckPolicy, VerdictController (/internal)
├── idempotency/   IdempotentExecution (public) · JDBC impl package-private
├── fx/            ExchangeRateProvider (public) · HTTP client package-private
├── outbox/        EventPublisher (public) · OutboxPoller package-private
├── mockfx/        the §28 stand-in — depends on nothing
└── common/        Money, Currency, problem-type constants
```

Dependencies run one way: `transfers` → `accounts`, `fx`, `idempotency`,
`outbox`. Nothing points back. The three public ports are exactly §3, §11/§12
and the FX client — hexagonal's value at the three places that asked for it,
without the ceremony everywhere else.

**The Java mechanism that makes this more than folder tidying, and which has no
TypeScript equivalent:** Java's *default* access level is package-private, and
it is compiler-enforced. A `TransferRepository` declared with no modifier is
literally uncallable from outside `transfers/`. The boundary is not a lint rule
or a convention — crossing it does not compile.

**Rejected — package-by-layer.** It makes the layer the only boundary, so "where
are your seams" answers "between controller and service", which is true of every
Spring application ever written.

**Rejected — hexagonal architecture.** A domain model, a JPA entity and a mapper
for every concept, buying isolation from a database we are not going to swap.

**Recommended, ~10 lines:** ArchUnit — `slices()…should().beFreeOfCycles()` plus
"no controller may reference a repository". It turns "I drew boundaries" into a
test that fails when someone crosses one. Spring Modulith would also do this,
but §12 declined it and reintroducing it for verification alone would read as
inconsistent.

**Trap:** `@Transactional` on a non-public method is **silently ignored** under
proxy-based AOP — no error, no warning, no transaction, and §4's phase-three
atomicity quietly stops existing. Spring 6 relaxed this for CGLIB proxies, but
the failure mode is silent either way. Rule: the **class** may be
package-private; the **`@Transactional` method** stays public.

> **Built in [ticket 03](03-package-skeleton-archunit.md)**, where the ArchUnit
> rules needed violation fixtures to be worth anything.
> **[Ticket 07](07-conversion-function.md)** adds to the `fx/` line above: the
> package exports a second public type beside the port, though not a second port.
> **[Ticket 10](10-list-accounts-and-seed.md)** adds an `AccountService` to the
> `accounts/` line, which the tree omits and this section's own ArchUnit rule
> requires.
> **[Ticket 09](09-create-account-endpoint.md)** builds the write path into it in
> the shape the trap above prescribes — package-private class, public
> `@Transactional` method — against a repository declaring only the methods the
> slice calls.
> **[Ticket 12](12-ordered-account-locking.md)** adds three more public types to
> that line — `AccountLocking`, `LockedAccounts` and `UnknownAccountException` —
> and is the first to depend on the `repository package-private` clause meaning
> something: `transfers` cannot reach a balance except through the operation that
> locks it. Still no port.
> **[Ticket 16](16-idempotent-execution.md)** amends the `idempotency/` line: the
> slice ships the storage under the port and not the port itself, and the
> implementation is JPA rather than the JDBC the tree names, on `accounts` and
> `transfers`' precedent. Nothing in the package is public, so the line's
> `IdempotentExecution (public)` is what ticket 17 makes true.
> **[Ticket 27](27-outbox-and-poller.md)** adds `OutboxEventRecorder` to the
> `outbox/` line, on ticket 07's precedent: a second public type, and still not a
> second port — there is one implementation and no seam. The count of ports above
> is unchanged, and the `OutboxPoller package-private` clause is load-bearing
> rather than decorative, since it is what makes "the only way to publish an event
> is to have written it down first" true by compilation.

---

## 31. Residual API decisions

- **No API versioning.** No `/v1` prefix → [deferred.md](../deferred.md).
- **No pagination.** All queries return everything →
  [deferred.md](../deferred.md).
- **Account creation:** `POST /api/accounts` takes a currency and an initial
  balance. **Currency is immutable thereafter** — an account that could change
  denomination would make every historical balance ambiguous.
- **Observability:** the Actuator health endpoint, plus §12's structured log
  line on publish. Nothing else.

> **[Ticket 10](10-list-accounts-and-seed.md)** settles the listing's wire shape
> against the immutable currency above: one `currency` field for all three
> figures, and a bare array rather than an envelope.
> **Account creation is built in [ticket 09](09-create-account-endpoint.md)**,
> whose `201` carries no `Location` header because no single-Account resource
> exists for one to address.
> **[Ticket 15](15-list-transfers-endpoints.md)** pays that debt on the Transfers
> side: it builds `GET /api/transfers/{id}`, so the `201` from
> `POST /api/transfers` now carries a `Location` — a relative reference, because an
> absolute one is built from the request's `Host` and this app sits behind a proxy in
> development. The Account half stays deferred, and stays deferred in that order:
> the endpoint first, the header with it.

