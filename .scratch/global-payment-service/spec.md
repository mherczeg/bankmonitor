# Spec: Global Payment Service

Status: ready-for-agent

Sources: [TASK.md](../../TASK.md) · [CONTEXT.md](../../CONTEXT.md) ·
[ADR-0001](../../docs/adr/0001-asynchronous-transfer-lifecycle.md) ·
[design decisions](../../docs/design-decisions/) (§ references are to `00-initial-decisions.md`) ·
[deferred.md](../../docs/deferred.md)

This spec covers the initial build. The architecture was settled in a grilling
session before any code was written, so this document does not re-decide it — it
turns that record into stories and a testing contract, and names the few places
the record left open.

---

## Problem Statement

An operator needs to move money between Accounts that may be denominated in
different Currencies, over a network and an Exchange Rate provider that both
fail intermittently, without ever moving the same money twice.

Three things make that hard, and all three are in the task's graded criteria:

- **A retry is indistinguishable from a second request.** A client that gets a
  timeout on `POST /api/transfers` does not know whether money moved. It must be
  able to send the identical request again and be certain the answer is the same
  one, not a second debit.
- **Money is not the only party involved.** A payment gateway sits in a larger
  architecture: Fraud Detection and Notification Center have to learn about
  every Transfer, and some of that class of service does not just *listen* — it
  *approves*. A design where a Transfer either completes or fails inside one
  HTTP request cannot grow an approver without being rebuilt.
- **The Exchange Rate provider is unreliable by specification.** It returns
  `503`s and it responds slowly. Handling that "elegantly" is explicitly graded,
  and where the call sits relative to the transaction that reserves funds
  determines whether a slow provider is a wasted second or a stuck Transfer.

Underneath all three: two requests touching the same Account concurrently must
not both pass an overdraft check, and two requests carrying the same Idempotency
Key must not both execute.

## Solution

A Spring Boot backend and a React frontend implementing an **asynchronous
Transfer lifecycle** ([ADR-0001](../../docs/adr/0001-asynchronous-transfer-lifecycle.md)).

A Transfer is created `PENDING`, with funds **reserved** on the source Account
rather than moved. In the same transaction, a policy writes one Check Ledger row
per Check the Transfer requires. The Transfer reaches `SETTLED` when every Check
has approved, `REJECTED` the moment any one rejects, and `EXPIRED` if the
deadline passes with Checks unanswered — each terminal state releasing the
Reserved Amount, and only `SETTLED` moving money.

That reframes every hard part of the problem into something with an answer:

- **"Undo a payment" becomes "don't make one."** A rejected Transfer never moved
  money, so there is no compensating transaction to write and no window in which
  a reversal can fail.
- **"Why is this stuck" becomes a query.** The Check Ledger is the pending-state
  UI, the audit trail and the test seam at once.
- **The task's `409 Conflict` becomes a real state** a client will actually hit
  and the frontend actually renders, rather than a millisecond race.
- **The flaky provider's failure stays in front of the caller.** The Exchange
  Rate is fetched at request time, outside any transaction, and locked onto the
  Transfer for its life — so a `503` is something the caller can retry, not an
  approved Transfer that can never settle.

Idempotency is a three-phase request: claim the key (its own committed
transaction, the unique constraint serialising duplicates), resolve the rate (no
locks held), then reserve funds *and* record the response in one transaction.
Concurrency is pessimistic row locks taken in ascending Account ID order, so
deadlock is structurally impossible rather than merely unlikely.

The frontend is Vite + React + TypeScript with four routes over the three
required screens, where TanStack Query owns all server state and an SSE message
is nothing more than a cache invalidation.

## User Stories

**Actor note:** the domain deliberately does not model a caller
([CONTEXT.md](../../CONTEXT.md): *User — deliberately unmodelled*). "Operator"
below names the human driving the screens, and "API client" the thing sending
requests; neither is a modelled entity. See Further Notes.

### Accounts

1. As an operator, I want to create an Account with a starting balance and a
   Currency, so that I have something to move money between.
2. As an operator, I want to choose the Currency from EUR, USD and HUF only, so
   that I cannot create an Account the Exchange Rate provider cannot quote.
3. As an operator, I want an Account's Currency to be fixed at creation, so that
   no historical balance on it is ambiguous.
4. As an operator, I want to see every Account with its balance, so that I can
   pick a source that can afford the Transfer I have in mind.
5. As an operator, I want to see each Account's Available Balance alongside its
   balance, so that I can tell how much of it is already committed to in-flight
   Transfers.
6. As an operator, I want amounts entered and displayed in the familiar decimal
   form for the Currency (2 places for EUR and USD, 0 for HUF), so that I never
   have to think in Minor Units.
7. As an API client, I want every Money amount on the wire to be an integer
   count of Minor Units in a field named `amountMinor`, so that no reader can
   mistake `10050` for a decimal quantity.
8. As an operator, I want a rejected Account creation to tell me which field was
   wrong, so that I can fix it without guessing.

### Requesting a Transfer

9. As an operator, I want to request a Transfer between two Accounts by choosing
   a source, a destination and an amount, so that money moves.
10. As an operator, I want the amount to be denominated in the source Account's
    Currency automatically, so that I cannot express a Transfer that claims EUR
    on a HUF Account.
11. As an operator, I want the amount field to show the source Account's
    Currency as an adornment, so that the denomination is visible without a
    field to get wrong.
12. As an operator, I want switching the source Account from a 2-decimal to a
    0-decimal Currency to immediately re-validate an amount like `100.50`, so
    that I learn about the problem before submitting.
13. As an operator, I want a Transfer from an Account to itself refused in the
    browser, so that I am not sent to the server for an answer the form already
    knows.
14. As an operator, I want a Transfer refused when the source Account's
    Available Balance is below the amount, so that no Account is overdrawn.
15. As an operator, I want a cross-Currency Transfer converted at an Exchange
    Rate fetched when I request it and fixed for that Transfer's life, so that
    the figure I was shown is the figure that settles.
16. As an operator, I want a same-Currency Transfer to skip the Exchange Rate
    provider entirely, so that an unrelated outage cannot block it.
17. As an operator, I want a conversion that rounds down to zero Minor Units
    refused, so that no Transfer debits the source and credits nothing.
18. As an operator, I want validation failures reported per field rather than as
    one sentence, so that the form can mark exactly what is wrong.
19. As an operator, I want submitting to take me to a page for that Transfer, so
    that its pending state lives in the URL and survives a refresh.

### Idempotency

20. As an API client, I want to send an Idempotency Key on every Transfer
    request, so that a network retry can never move money twice.
21. As an API client, I want a request with no Idempotency Key rejected with
    `400`, so that the guarantee cannot be opted out of by accident.
22. As an API client, I want a key that is not a well-formed UUID rejected with
    `400`, so that key-squatting is a 122-bit problem rather than a guess.
23. As an API client, I want a repeat of a *succeeded* key and payload to return
    the original `201 Created` result, so that my retry is indistinguishable
    from my first call.
24. As an API client, I want a repeat of an *in-progress* key to return `409
    Conflict` with a `Retry-After`, so that I know to wait rather than to give
    up.
25. As an API client, I want a repeat of a *failed* key to actually execute, so
    that a transient failure is recoverable without minting a new key.
26. As an API client, I want a key reused with a *different* payload to return
    `409` under a distinct problem `type` URN and with no `Retry-After`, so that
    I can tell "wait and retry" from "you have made a mistake, never retry
    this".
27. As an API client, I want two concurrent requests carrying the same key to
    produce exactly one `201` and one `409`, so that the guarantee holds under
    the race it exists for.
28. As an API client, I want two concurrent retries of a *failed* key to result
    in exactly one execution, so that the recovery path is not itself a
    double-charge.
29. As an operator, I want the Idempotency Key generated once when the form
    becomes ready and held until a Transfer succeeds, so that the key identifies
    what I meant to do rather than an individual HTTP attempt.
30. As an operator, I want my key reset only after a success, so that correcting
    a rejected amount and resubmitting is a new intent rather than a replay.

### Concurrency and integrity

31. As an operator, I want two concurrent Transfers out of the same Account to
    never both pass the overdraft check, so that the Account cannot go negative.
32. As an operator, I want two Transfers moving money in opposite directions
    between the same pair of Accounts to never deadlock, so that neither request
    fails for a reason that has nothing to do with the money.
33. As an operator, I want a Transfer's Reserved Amount held from request until
    the Transfer reaches a terminal state, so that the same funds cannot be
    committed twice.
34. As an operator, I want the balance check to happen after the Account is
    locked, so that the figure it tests against cannot be invalidated before the
    reservation is written.

### The Transfer lifecycle

35. As an operator, I want a newly requested Transfer to be `PENDING` rather
    than complete, so that a Check has somewhere to intervene.
36. As an operator, I want a Transfer's Check Ledger written in the same
    transaction as the Transfer, so that a Transfer can never exist without its
    checklist.
37. As an operator, I want to see which Checks a `PENDING` Transfer is still
    waiting on, so that "why is this stuck" has an answer on the screen.
38. As a Check service, I want to report a Verdict of approved or rejected for
    one Check on one Transfer, so that the Transfer can progress.
39. As a manual approver, I want to report my decision through the same
    operation an automated service uses, so that a human and a service are
    interchangeable to the orchestrator.
40. As an operator, I want a Transfer to settle only when every Check has
    approved, so that no Check can be bypassed.
41. As an operator, I want Settlement to lower the source Account's balance
    *and* its Reserved Amount while raising the destination's balance, so that
    the reservation is consumed rather than left behind.
42. As an operator, I want a Transfer rejected the moment any single Check
    rejects, so that the remaining Checks are not waited on pointlessly.
43. As an operator, I want a rejected Transfer to release its Reserved Amount
    without moving money, so that the funds are spendable again immediately.
44. As a Check service, I want reporting the same Verdict twice to advance the
    Transfer only once, so that my own at-least-once delivery is safe.
45. As an operator, I want a `PENDING` Transfer whose deadline passes to become
    `EXPIRED` and release its Reserved Amount, so that an unresponsive Check
    cannot freeze funds forever.
46. As an operator, I want a Verdict arriving after Expiry to be refused rather
    than settle the Transfer, so that a late approval cannot move money that was
    already released.
47. As a maintainer, I want adding a new Check type to be a policy line and a
    ledger row, so that the extensibility ADR-0001 was chosen for is real.
48. As an operator, I want a Transfer's locked Exchange Rate stored with the
    timestamp it was fetched, so that a settled conversion is auditable.

### Integration with other domain services

49. As a downstream domain service, I want an Outbox Event written in the same
    transaction as the change it describes, so that a committed Transfer and its
    event cannot disagree.
50. As a downstream domain service, I want to be told about every Transfer that
    settles, so that Notification Center can act without polling.
51. As a Check service, I want to be *pushed* a `CheckRequested` event carrying
    the Transfer's payload, so that I do not have to call back for the amount.
52. As a downstream domain service, I want at-least-once delivery, so that a
    publish failure loses nothing — I will deduplicate, which I must do anyway.
53. As a maintainer, I want the outbox poller to publish through a one-method
    interface, so that swapping a log line for Kafka is a transport change and
    not a redesign.
54. As a reviewer, I want a profile-gated stub Fraud Detection consumer that
    approves or rejects a beat later, so that the asynchronous lifecycle is
    visible in the running UI rather than taken on faith.
55. As a maintainer, I want the internal Verdict endpoint to require a shared
    secret, so that nobody can approve their own Transfer and walk past fraud
    screening.

### The flaky Exchange Rate provider

56. As an operator, I want a slow Exchange Rate provider to hit a configured
    timeout, so that a request cannot park forever holding an in-progress
    Idempotency Key.
57. As an operator, I want a `5xx` or a timeout from the provider retried a
    bounded number of times, so that an intermittent fault is invisible to me.
58. As an operator, I want a `4xx` from the provider never retried, so that a
    deterministic error is not turned into four of them.
59. As an API client, I want exhausted retries to return `503` with a problem
    `type` naming the provider and a `Retry-After`, so that I know the failure
    is not mine and is worth retrying.
60. As an API client, I want a provider failure to leave the Idempotency Key
    `FAILED`, so that resubmitting with the *same* key executes properly rather
    than replaying a failure.
61. As a maintainer, I want a test that asserts a retry actually occurred, so
    that resilience is demonstrated rather than claimed.
62. As a maintainer, I want the mock provider to be a real HTTP endpoint the
    real client calls over the network, so that the timeouts, retries and error
    mapping being demonstrated are not the things being mocked out.
63. As a maintainer, I want the mock provider excluded from the application's
    security, error-handling, logging and CORS layers, so that it stays a
    believable third party rather than a part of our app wearing a costume.

### Viewing Transfers

64. As an operator, I want one Transactions list showing Transfers in every
    state, so that a `PENDING` Transfer is not invisible on the only list
    screen.
65. As an operator, I want a status column and an optional status filter, so
    that I can narrow to settled Transfers when I want to.
66. As an operator, I want a Transfer's page to update live as its Checks
    answer, so that I do not have to refresh to learn the outcome.
67. As an operator, I want the live update to work after a page refresh, so that
    the pending state is not lost with component state.
68. As an operator, I want the Accounts list to be correct when I navigate to it
    after watching a Transfer settle, so that a balance I just saw change is not
    stale.
69. As an operator, I want the Accounts and Transactions screens to refetch when
    I return focus to the window, so that a list nobody was looking at is not
    silently out of date.
70. As an operator, I want a dropped live connection to reconnect and converge
    on the truth, so that a missed event costs me nothing.
71. As a maintainer, I want the live channel to carry only a Transfer ID and an
    event type, so that a message is a cache invalidation and never something to
    merge or reconcile.

### Errors, build and operation

72. As an API client, I want every error to be an RFC 9457 problem document with
    the `type` URN as the sole discriminator, so that I branch on one field and
    never on two that can disagree.
73. As an operator, I want an error to be shown as a readable title and body
    with a clear indication of whether retrying will help.
74. As a maintainer, I want frontend API types generated from the backend's
    OpenAPI document, so that a mock returning a shape the backend no longer
    produces is a failed build rather than a green test.
75. As a maintainer, I want each slice to ship its own migration with
    `ddl-auto=validate` on from the first commit, so that a naming mismatch
    fails at startup naming the exact column instead of arriving all at once at
    the end.
76. As a maintainer, I want demo seed data in a dev-profile runner rather than a
    migration, so that it does not run inside the test suite.
77. As a maintainer, I want an architecture test enforcing the package
    boundaries, so that "I drew seams" is a test that fails when someone crosses
    one.
78. As a reviewer, I want to build, run and test the whole application with
    documented commands and no Docker, so that nothing about my machine can make
    the suite fail.
79. As an operator, I want a health endpoint, so that the service can be
    monitored.

## Implementation Decisions

Every decision below is recorded in full — with what was rejected and why — in
[design-decisions/00-initial-decisions.md](../../docs/design-decisions/00-initial-decisions.md).
Section numbers are
given so the reasoning is one hop away; this list is the shape, not the
argument.

### Modules

Package-by-feature under one Spring Boot application (§30), with dependencies
running one way — `transfers` → `accounts`, `fx`, `idempotency`, `outbox`, and
nothing pointing back:

| Module | Public surface | Package-private |
|---|---|---|
| `accounts` | Account, its controller | repository |
| `transfers` | Transfer, its controller, the service owning the state machine | — |
| `transfers/checks` | Check Ledger, Check policy, the `/internal` Verdict controller | — |
| `idempotency` | `IdempotentExecution` | JDBC implementation |
| `fx` | `ExchangeRateProvider` | HTTP client |
| `outbox` | `EventPublisher` | outbox poller |
| `mockfx` | the stand-in provider — depends on nothing | — |
| `common` | Money, Currency, problem-type constants | — |

The three public ports are the only ports. Java's default access level is
package-private and compiler-enforced, so crossing a module boundary does not
compile — this is the mechanism that makes the structure more than folder
tidying (§30).

**Trap:** `@Transactional` on a non-public method is silently ignored under
proxy-based AOP. The *class* may be package-private; the `@Transactional`
*method* stays public.

### Domain model

- **`Account` is the atomic entity; no `User` is modelled** (§1). It is a Java
  `record`, so adding an owner later is additive.
- **Money is a `long` count of Minor Units** paired with a Currency, as a JPA
  `@Embeddable` (§16). `BigDecimal` was rejected — not for drift but because it
  is unconstrained, and because its `equals` compares scale.
- **Two balance fields on `Account`:** `balance` and `reservedAmount`; Available
  Balance is their difference and is what the overdraft check tests (§13).
- **Per-Currency decimals live only at the edges** — form parsing and display.
  The core never divides by 100. The Exchange Rate stays `BigDecimal`; it is the
  one value that is genuinely a decimal and it is never Money.
- **The single conversion**, and the most error-prone line in the backend:

  ```
  destMinor = round(srcMinor × rate × 10^(destScale − srcScale))     HALF_EVEN
  ```

  `422` when the result rounds to zero. Money is knowingly *not conserved*
  across the two Accounts — the remainder vanishes, which is what the
  double-entry deferral costs.

### Transfer lifecycle

- **`PENDING` → `SETTLED` / `REJECTED` / `EXPIRED`**, funds reserved at request
  and moved at Settlement
  ([ADR-0001](../../docs/adr/0001-asynchronous-transfer-lifecycle.md), §7).
- **Orchestration, not choreography** (§8). The transfer service owns the state
  machine; a policy writes one Check Ledger row per required Check in the same
  transaction as the Transfer. Settles when no row is pending and none rejected;
  rejects on the first rejection.
- **Verdicts arrive by inbound HTTP callback** at
  `POST /internal/transfers/{id}/checks/{check}`, as a thin adapter over a
  `recordVerdict(transferId, check, outcome)` domain operation (§9). A broker
  consumer would be a second adapter over the same operation. The asymmetry with
  outbound events is deliberate: outbound needs an outbox because *we* own the
  atomicity of "the Transfer committed, therefore the event exists"; inbound
  owns no such thing and only needs to be idempotent.
- **Idempotent advancement via conditional update.** `UPDATE ... WHERE
  status = ?` plus a rows-affected check, so exactly one caller wins. The same
  pattern recurs for the failed-key retry (§5), for Verdicts (§8) and for Expiry
  (§14) — it is one idea used three times, not three mechanisms.
- **A scheduled reaper expires overdue Transfers** and releases their
  reservations (§14). Cheap, because the scheduled-poller machinery already
  exists for the outbox.
- **The Exchange Rate is locked at request time** and stored with its fetch
  timestamp (§15). Fetching at Settlement would call the flaky provider after
  every approval, leaving an approved Transfer that cannot settle and no caller
  to tell.
- **The quote's validity window and the Check deadline are the same clock**, so
  a Transfer that outlives its quote expires rather than settling on a stale
  rate.

### Idempotency and concurrency

- **Idempotency lives in the service layer behind a seam**, not in the request
  pipeline (§3). A filter-level check decides *before* the money-moving
  transaction opens, leaving exactly the race the requirement is about. The
  interface, which encodes the decision more precisely than prose:

  ```java
  public interface IdempotentExecution {
      <T> T executeOnce(String key, String payloadHash, Supplier<T> operation);
  }
  ```

  Named replacements it holds a place for: pessimistic `SELECT ... FOR UPDATE`,
  a Redis-backed store once there is more than one instance, or folding into the
  outbox.

- **Three phases** (§4): claim the key as `IN_PROGRESS` in its own immediately
  committed transaction, where the unique constraint serialises duplicates →
  resolve the Exchange Rate with no transaction open and no locks held →
  reserve funds, create the Transfer and its Check Ledger, *and* flip the
  idempotency record to `SUCCEEDED` with its stored response, all in one
  transaction. Phase three bundles the status update deliberately: separate
  commits would allow a crash to strand reserved funds behind a permanent
  `409`.

- **Duplicate resolution** happens at the top of `executeOnce`, before the FX
  call, so a duplicate costs nothing (§5):

  | Existing status | Response |
  |---|---|
  | `IN_PROGRESS` | `409`, `urn:problem:request-in-progress`, with `Retry-After` |
  | `SUCCEEDED` | replay the stored `201` |
  | `FAILED` | claim by conditional update, then execute |
  | different payload hash | `409`, `urn:problem:idempotency-key-reused`, no `Retry-After` |

  Marking `FAILED` needs `REQUIRES_NEW` propagation, or the rollback undoes the
  status write and strands the row at `IN_PROGRESS`. Keys must be well-formed
  UUIDs (`400` otherwise).

- **Pessimistic locks in ascending Account ID order** (§6), never by role in the
  Transfer, so opposing Transfers contend for the same lock first and deadlock
  is structurally impossible. Viable only because phase two moved the FX call
  out of the transaction. The lock is taken before the balance check.
  Self-Transfers are refused with `422` before locking.

### Integration

- **Hand-rolled transactional outbox** (§12): an event row written in the same
  transaction as the Transfer, a `@Scheduled` poller publishing unsent rows
  through a one-method `EventPublisher` and marking them sent. At-least-once.
  Kafka is the transport under `publish()`, not a replacement for the outbox.
- **Check services are pushed to via the same outbox** (§11), with
  `CheckRequested` as a second event type. Polling was rejected: it costs a
  query API, cursor semantics and claim semantics *in addition to* the outbox.
- **Event fatness is deliberately asymmetric** (§17): outbound events to
  services carry the payload; the browser-facing stream carries only
  `{ type, transferId }`, because the browser can call our API and a service
  calling back for the amount is the coupling §11 rejected polling to avoid.
- **In this build `publish()` writes a structured log line**, and a
  profile-gated stub Fraud Detection consumer reports a Verdict a beat later —
  a fake consumer over a real mechanism, so the lifecycle is visible in the UI.
  It can reject as well as approve.
- **`/internal/**` sits behind a configured shared-secret header** while
  `/api/**` is `permitAll` (§10). Not a contradiction of the auth deferral —
  that declined to model *user* identity; this is service-to-service trust
  across a boundary the async model created.

### The Exchange Rate provider

- **Timeouts, `@Retryable`, then fail to the caller** with `503`,
  `urn:problem:fx-provider-unavailable` and a `Retry-After` (§27). No cache, no
  circuit breaker — a breaker protects a scarce thread pool, and virtual threads
  mean threads are not scarce.
- **Spring Framework 7 has native retry**, so no `spring-retry` and no
  Resilience4j. Two details every tutorial predates: the enabling annotation is
  `@EnableResilientMethods`, and `maxAttempts` is now `maxRetries`.
- Retry `5xx` and timeouts; **never retry `4xx`**.
- **Failing to the caller is not giving up** — §5 marks the key `FAILED`, which
  is retryable, so the client resubmits with the same key and the server
  executes properly. Idempotency is what makes the lean retry policy complete.
- **The mock provider is a real HTTP endpoint inside the app**, profile-gated,
  called over real HTTP by the real client reading a configurable base URL
  (§28). A stub `@Bean` was rejected because it sits *above* the HTTP client, so
  none of the timeouts or retries being demonstrated would ever run.
- **The mock must stay out of the app's cross-cutting layers** or it stops being
  a believable third party: security bypass rather than `permitAll`, a scoped
  rather than global exception handler, explicit filter URL patterns, and out of
  the CORS mapping entirely.
- **Consequence:** the application now calls itself over HTTP, which makes
  virtual threads **load-bearing** rather than a nicety — on a classic thread
  pool the inbound request can hold a thread waiting for a second one to serve
  its own outbound call.

### API contract

- **RFC 9457 `ProblemDetail`** via `spring.mvc.problemdetails.enabled`, with the
  `type` URN as the **sole** discriminator (§18). A hand-rolled envelope was
  rejected because Spring already emits `ProblemDetail` for `@Valid` rejections,
  `415`, `405` and malformed JSON — a custom shape would be a *second* error
  format, not a replacement.
- **Field-level validation errors as an extension member**, because Spring's
  default packs every violation into one unusable sentence.
- **`Retry-After` present on the in-progress `409`, absent on the key-reuse
  `409`** — the retryable case is machine-readably marked as such.
- **Transfer payload is `{ fromAccountId, toAccountId, amountMinor }`** plus
  `X-Idempotency-Key`. **Currency is not in the payload** — it is derived from
  the source Account, which deletes a whole bug class (§22).
- **`POST /api/accounts`** takes a Currency and an initial balance; Currency is
  immutable thereafter (§31).
- **`GET /api/transfers`** returns Transfers in every state with an optional
  `?status=` filter (§19). The task's *"végrehajtott tranzakciók"* was written
  against a synchronous model; under ADR-0001 the faithful reading is
  "requested".
- **`GET /api/events/stream`** — one SSE endpoint, not one per Transfer, with
  **no catch-up**: no `Last-Event-ID`, no replay buffer (§17). *The stream
  carries hints; the REST endpoint carries truth.* The client refetches when the
  stream opens, which converges from any missed state and reuses the first-load
  path.
- **No API versioning and no pagination** — both deferred with reasons.

### Frontend

- **Vite + React + TypeScript, two processes** (§20). Next.js was rejected: no
  SSR or SEO requirement, RSC actively fights an app whose data is behind a Java
  API and whose interesting components are all stateful, and decisively it adds
  a **second production runtime** beside Spring.
- **TanStack Router**, chosen for typed route configuration as a deliberate
  trade against ecosystem size (§21). Four routes: `/accounts`, `/transfers/new`,
  `/transfers/:id` (the only SSE consumer), `/transfers`.
- **Submitting navigates to `/transfers/:id`**, so pending state lives in the
  URL and survives a refresh — the frontend half of ADR-0001.
- **TanStack Query owns all server state.** The payoff of thin events is that an
  SSE message becomes nothing but `invalidateQueries` — no merging, no
  reconciliation, no ordering logic. `refetchOnWindowFocus` covers the two
  screens the live scope deliberately excludes. **Query's retry must be
  narrowed** to `5xx` only, or a `422` gets retried as noise and a `409` races
  its own `Retry-After`.
- **No global state library** (§22). Once Query owns server state and the router
  owns route state there is nothing left to manage; the escalation, if it were
  ever needed, is Context + `useReducer` before a library.
- **TanStack Form + zod.** As of v1, Form speaks Standard Schema natively —
  `zodValidator` and the adapter package are gone; pass the schema straight in.
  **Transforms do not flow through validation**, so `onSubmit` receives
  input-typed data and the decimal → Minor Unit conversion is one explicit
  `schema.parse` line inside `onSubmit`.
- **The schema is a factory over the accounts list**, so the decimal-scale rule
  derives from the selected source Account and switching Currencies re-validates
  with no manual dependency wiring. Cross-field rules — including the
  self-Transfer check — live in the form-level validator.
- **Bootstrap 5, CSS only, plus CSS Modules** (§23). Component libraries were
  rejected on a specific ground rather than taste: **this app has no
  JavaScript-driven components** — no dialog, combobox, popover or dropdown — so
  Radix's entire proposition buys nothing. No `react-bootstrap` and no Bootstrap
  JS bundle; a single CSS import.
- **Frontend API types are generated from the backend's OpenAPI document**
  (§26). This is the only thing standing between end-to-mock tests and
  self-congratulation: it converts mock drift into a compile error, and puts the
  problem-type URNs in exactly one place.

### Build and schema

- **Maven** (§29) — `./mvnw` means the reviewer installs nothing, and there is
  no frontend plugin to justify Gradle.
- **Flyway with `ddl-auto=validate`, from the first commit** — the migration
  directory shows every table, constraint and index as SQL, and is the natural
  home for the indexes the design implies (the outbox poller's unsent predicate,
  the reaper's overdue predicate). From day one because Hibernate's implicit
  naming maps `fromAccountId` and the Money embeddable in ways that are cheap to
  catch one at a time and expensive to catch all at once. **One migration per
  slice** — §29's original single `V1__init.sql` was amended during ticket
  breakdown, because writing the outbox and check-ledger tables before those
  entities exist is schema written against a design instead of against code.
- **Seed data is not schema** — demo Accounts go in a dev-profile runner, never
  in a migration, because a Flyway seed runs in the test suite too.
- **Spring Security is configured, not disabled**, with each setting justified:
  CSRF off (stateless JSON, no cookies), `STATELESS` sessions, CORS enabled for
  the Vite dev origin, `permitAll` on `/api/**` — plus the one real rule on
  `/internal/**`. **Watch out:** Security runs before MVC and intercepts CORS
  preflight, so this needs `.cors(...)` *and* a `CorsConfigurationSource` bean;
  `@CrossOrigin` alone will not work.
- **ArchUnit**, ~10 lines: slices free of cycles, plus "no controller may
  reference a repository".

## Testing Decisions

### What makes a good test here

**Test external behaviour, not implementation.** A test should name a thing an
operator, an API client or a Check service can observe — a status code, a
balance, a rendered row, a published event — and should survive any refactor
that keeps that observation true. A test that asserts a method was called on a
collaborator is asserting the current implementation, and will fail for a change
that broke nothing.

**Two consequences specific to this build:**

- **The difficulty lives where unit tests cannot reach.** "The unique constraint
  serialises concurrent duplicates", "ascending-ID locking makes deadlock
  structurally impossible", "the conditional update returns 1 for exactly one
  caller" are claims about the *database*, as is every transaction boundary in
  the lifecycle. *Konkurencia és adatintegritás* is a named graded requirement,
  so these are the tests that count — and none of them can be unit tests.
- **"Unit test the logic" is a design instruction, not a testing one.**
  `decide(ledgerRows) → Settle | Reject | Wait` is testable; the same logic
  inside a service that also does I/O is not. The same move applies on the
  frontend: pull logic into pure TypeScript modules and unit test those, and
  write as few React integration tests as possible. Integration confidence comes
  from a real browser, not from jsdom.

### Seams

**Two test-entry seams, one per runtime**, joined statically so they cannot
drift apart.

**Seam 1 — the backend HTTP API.** `/api/accounts`, `/api/transfers`,
`/api/events/stream`, `/internal/transfers/{id}/checks/{check}`. Every backend
behaviour that matters is reachable here. **Verdicts are driven over HTTP**
rather than by calling `recordVerdict` directly, even though §9 names the domain
operation as the seam for *adapters*: going in through `/internal` covers the
shared-secret rule, the adapter and the domain operation with one entry point,
and keeps the backend at exactly one. Direct calls to `recordVerdict` are
reserved for cases where HTTP demonstrably adds nothing.

*Consequence to honour:* the stub Fraud Detection consumer must be **off** in
tests, or it races the test's own Verdicts.

**Seam 2 — a real browser against a scripted network.** Playwright with
`page.route()` for HTTP, and `window.EventSource` stubbed via an init script
that runs before app code. Real focus and blur, real navigation, real form
behaviour. Mock with `page.route`, **not MSW-in-browser** — no service worker,
no extra build mode, and the mocks live in the spec that depends on them.

*Why the EventSource stub rather than a streamed response:* Playwright's
`route.fulfill()` takes a string or a buffer only — there is no streaming body —
so an SSE event cannot be pushed mid-test, which is exactly what asserting
`PENDING → SETTLED` needs. The stub makes the sequence deterministic with no
`waitForTimeout`: assert the row reads pending → re-route the Transfer endpoint
to return the settled version, so *truth changes* → dispatch a message event on
the fake source → assert the row reads settled. That drives the whole
thin-event → invalidate → refetch → render design end to end.

**Joining the two seams: generated OpenAPI types.** Without them, seam 2 proves
only that the frontend handles shapes the *test author* invented. With them, a
mock returning a shape the backend no longer produces is a failed build.

**Three substitution seams, for replacement rather than entry** — already the
public ports of §30: `IdempotentExecution`, `EventPublisher`,
`ExchangeRateProvider`.

**The FX test double sits *below* `ExchangeRateProvider`, on the wire.**
`MockRestServiceServer` binds to the client builder and scripts `503, 503, 200`
with no new dependency. Doubling the Java interface instead would put the
failure simulation above the HTTP client, so the timeouts, retries and error
mapping being demonstrated would never run — the thing being demonstrated would
be the thing being mocked out. `MockRestServiceServer` sits above the transport,
though, so the read timeout is the one path this seam cannot reach; that gap is
deferred with its reopening condition named.

### Backend test layers

There is no prior art in the repo — this is the first code. The design record's
own table is the prior art the suite should follow, and **which annotation you
reach for is itself the design decision**:

| Layer | What Spring starts | DB? | Used for |
|---|---|---|---|
| plain JUnit | nothing | no | the conversion, round-to-zero, the Check policy, the ledger decision function |
| `@WebMvcTest` | controllers, JSON, validation, exception handlers, security | no | status codes, problem-type URNs, missing and malformed Idempotency Keys, field-level validation bodies, the `/internal` shared secret |
| `@DataJpaTest` | JPA + in-memory DB, no web | yes | the idempotency unique constraint, conditional-update rows-affected, the pessimistic-lock query |
| `@SpringBootTest` | everything | yes | concurrent same-key → exactly one `201` and one `409`; opposing Transfers do not deadlock; create → outbox → Verdict → `SETTLED`; Expiry releases the reservation; a Verdict after Expiry is refused; FX fails twice then succeeds |

Target roughly 15–20 backend tests. **Tests run on H2**, which is what the
application also ships on — Testcontainers was rejected because `./mvnw test`
failing for want of a running Docker daemon is a bad first impression bought
very cheaply, and because nothing outside the JVM needs a realistic database
once Playwright is end-to-mock.

**Three traps that will silently produce green tests that prove nothing:**

- **`@Transactional` on a concurrency test method defeats it.** Spring runs the
  test in one transaction, so the second thread cannot see the first thread's
  uncommitted claim, both "succeed", and the test passes while proving nothing.
  Write these non-transactional with manual cleanup.
- **Without a `CountDownLatch`, the race never happens** — thread one finishes
  before thread two starts. Line them up on a virtual-thread executor and assert
  **exactly** one `201` and one `409`.
- **Context caching is per-configuration.** `@SpringBootTest` boots the context
  once and reuses it, but every distinct combination of mocked beans, active
  profiles and property overrides creates a *separate* cached context. Careless
  variation turns one five-second boot into eight.

**A free win worth taking:** Spring Framework 7 publishes a retry event, so a
test can assert the retry actually happened via an event listener rather than by
mocking framework internals — resilience tested rather than claimed, on a graded
line.

### Frontend test layers

**Vitest + React Testing Library** — Vitest because Vite is already there: same
config, same transform pipeline, no second toolchain.

**Extract, then unit test.** The extraction list is the test list: money
formatting and parsing, the Transfer schema factory, problem-document handling,
the Idempotency Key lifecycle, and SSE event handling. Applying the rule turns
the three tests that matter most into plain unit tests with no React:

- **Idempotency Key lifecycle** — generated once when the form becomes ready,
  held in a ref across retries, reset only after success. Generating it inside
  the mutation function defeats the entire mechanism: every retry gets a fresh
  key and the server sees a new Transfer. This is the frontend half of a graded
  requirement, and it is a plain module.
- **Problem-document handling** — `problem → { title, body, retryable }` is a
  pure table test over every URN the backend can emit.
- **SSE handling** — `event → QueryKey[]` is pure; React only iterates the keys
  and invalidates. **This makes the SSE yak-shave disappear:** no jsdom
  `EventSource` polyfill, no streaming mock, no flake.

**MSW probably drops entirely** — Playwright owns network mocking, and the
surviving RTL tests ("does this field show its error") touch no network.

**Playwright is end-to-mock, not true E2E**, and the gap is named rather than
glossed: it proves the frontend handles every response shape correctly and
proves nothing about whether the backend produces those shapes. Generated types
close most of that statically — structure, not behaviour. True E2E is deferred
with its shape written down.

## Out of Scope

Every item is recorded with its reasoning and its "what it would take" in
[deferred.md](../../docs/deferred.md), which feeds the README's TODO section —
graded as heavily as the code.

**Not built, by decision:**

- **Authentication and any notion of a caller.** Spring Security is wired with
  an explicit, justified stateless chain, but `/api/**` is `permitAll`.
- **Account ownership** (`User` / `Customer`).
- **Scoped queries and event streams.** Every list returns everything to anyone
  and the stream pushes every Transfer's events to every subscriber. The
  frontend's client-side filter is a rendering convenience, **not a boundary**.
  This is a consequence of the two deferrals above, not an independent choice.
- **Per-caller Idempotency Key namespacing.** Keys are global, with UUID
  well-formedness as the mitigation against key-squatting.
- **A double-entry ledger and internal accounts** — and with it, **the rounding
  remainder has nowhere to go**. Money is knowingly not conserved across a
  cross-Currency Transfer.
- **Running more than one instance.** Two known breaks: the outbox poller would
  double-publish, and event streams are instance-local.
- **Outbox robustness** — no retry with backoff, no dead-letter path, no
  ordering guarantee between two events on one Transfer, no archival.
- **A separate port for `/internal/**`** — shared secret on the same connector.
- **A terminal-vs-retryable failure taxonomy.** Any reservation-time failure is
  `FAILED` and retryable.
- **Re-quoting the Exchange Rate at Settlement** — a business decision about who
  carries FX movement risk, not a technical one.
- **A circuit breaker on the FX provider.**
- **Single-jar packaging** — deferred *together with* the SPA-fallback 404 trap
  it creates, so that trap is not rediscovered from scratch.
- **API versioning** and **pagination**.

**Test coverage consciously not attempted:**

- **Verifying the locking design against Postgres.** The residual risk is real
  and named: H2's default lock timeout is about a second where Postgres waits
  indefinitely, so a test that passes here can fail there on timing alone. Treat
  this as a **production prerequisite**, not a nice-to-have — it is the check on
  the design's central integrity claim.
- **The FX read timeout**, which `MockRestServiceServer` cannot reach.
  **Unlike the other deferrals this one may reopen during the build**: if the
  timeout path needs real coverage while writing the suite, re-adding WireMock
  for that single test is the answer — it is test-scoped and costs the reviewer
  nothing.
- **True end-to-end testing** — a real browser against the real application.
  The awkward part is not the wiring but the waiting: the stub consumer's delay
  becomes test timing.

**Not a deliverable:** a `PROMPTS.md` in the form the task describes. The AI-use
documentation is instead the committed session logs plus a workflow write-up.

## Further Notes

### Verify in the first hour

Two decisions bet that the ecosystem has caught up to Spring Boot 4. Both are
cheap to check now and expensive to discover late — do them before writing
domain code:

1. **Hibernate maps a Java `record` as `@Embeddable`.** Supported since
   Hibernate 6.2 and Boot 4 ships Hibernate 7, but if this is wrong every
   entity's shape changes.
2. **`springdoc-openapi` supports Spring Boot 4.** If not, the generated-types
   safety net that joins the two test seams does not exist, and the end-to-mock
   testing strategy needs rethinking rather than patching.

### Other traps recorded before they are hit

- **The Vite dev proxy must not buffer the SSE stream**, or events arrive in a
  clump when the connection closes and the live UI appears broken in development
  only.
- **TanStack Router needs `"strict": true`** in `tsconfig.json` or its inference
  silently degrades. Decide up front whether the generated route tree is
  committed — a half-committed generated file is a confusing diff.
- **`z.input` vs `z.output`** (`z.infer` aliases *output*) is worth a comment at
  the one place it bites.

### A glossary gap

[CONTEXT.md](../../CONTEXT.md) deliberately leaves `User` unmodelled, which is
correct for the domain. But the user stories above needed a word for *the human
driving the screens* and there isn't one — "operator" is invented here, not
drawn from the glossary. Per the domain-docs convention that is a signal worth
recording rather than quietly resolving: either the stories should be written
purely in terms of API clients and Check services, or the glossary should name
the actor and say it is unmodelled for the same reason `User` is. Worth a
`/domain-modeling` pass; not blocking.

### Sequencing

Nothing here dictates build order, but two things are cheap now and expensive
later, and both were argued for on those grounds: **Flyway with `validate` from
the first commit**, and the two ecosystem checks above.

Build order is now recorded as tickets in
[issues/](./issues/), numbered in dependency order with a `Blocked by:` line
each.

### Reading order for an implementer

[ADR-0001](../../docs/adr/0001-asynchronous-transfer-lifecycle.md) first — it is
the one decision a fresh reader would otherwise "simplify" back to synchronous,
and §§7–15 of the design record are its mechanics. Then
[00-initial-decisions.md](../../docs/design-decisions/00-initial-decisions.md) §§3–6 for the request
pipeline, which is where the graded concurrency requirements are actually
answered.
