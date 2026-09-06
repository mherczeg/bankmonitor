# Design decisions

What was chosen, why, and what was rejected — split so that the design as it was
*planned* stays separable from what the build then found out.

- **[00 — initial decisions](00-initial-decisions.md)** is the whole design as it
  stood before the first ticket was picked up: §1–§31, settled in a grilling
  session on 2026-09-05. It is a fixed point, not a living document.
- **One file per ticket**, holding only what that ticket settled, corrected or
  contradicted. A ticket that changed nothing about the design has no file.

So a section of §1–§31 tells you what was intended, and the tickets listed
against it tell you what happened. Neither file rewrites the other.

Deferrals live in [../deferred.md](../deferred.md); the one decision that shapes
all the others is [ADR-0001](../adr/0001-asynchronous-transfer-lifecycle.md);
domain vocabulary is in [CONTEXT.md](../../CONTEXT.md). The tickets themselves
are in [`.scratch/global-payment-service/issues/`](../../.scratch/global-payment-service/issues/).

## Ticket records

| Ticket | What it settled |
|---|---|
| [01 — project skeleton](01-project-skeleton.md) | both Spring Boot 4 ecosystem bets won; what `@Enumerated(STRING)` actually emits; Boot 4's moved test furniture |
| [02 — Flyway wiring](02-flyway-wiring.md) | `spring-boot-flyway`, not `flyway-core` — the coordinate that silently runs nothing |
| [03 — package skeleton and ArchUnit](03-package-skeleton-archunit.md) | why an ArchUnit rule that matches nothing needs its own violation fixtures |
| [04 — security chain and CORS](04-security-chain-cors.md) | deny-by-default, and three rejected alternatives |
| [05 — problem detail contract](05-problem-detail-contract.md) | why `spring.mvc.problemdetails.enabled` is absent; URN chosen from status, not exception |
| [06 — money and currency](06-money-and-currency.md) | `Money`'s four-method surface; two corrections to §29's mapping assumptions |
| [07 — the conversion function](07-conversion-function.md) | round-to-zero as a sealed outcome, not an empty `Optional`; why it lives in `fx`; the rate guard the shape needed |
| [08 — the Account entity](08-account-entity.md) | the identifier no section had chosen; why a `record` entity boots and then fails; two invariants in the table |
| [09 — creating an Account](09-create-account-endpoint.md) | why a typed `Currency` forced ticket 05's contract to widen; the Jackson default that truncates 100.50 into 100 |
| [10 — listing Accounts, and seed data](10-list-accounts-and-seed.md) | the service §30's tree omits and its own rule requires; two slice tests that assumed an empty scan root |
| [11 — the Transfer entity](11-transfer-entity.md) | `in (…)` in a check constraint is broken on H2; Accounts by ID rather than by association; one timestamp, not two |
| [12 — ordered Account locking](12-ordered-account-locking.md) | why two locking statements rather than one ordered query; which half of §6 each test can prove; why the locked-path rule's origin is a list and not a query over callers |
| [13 — reserving funds under concurrency](13-reserve-funds.md) | a start latch left the deadlock test green under a broken lock order — measured, and fixed with a barrier at the lock acquisition; the three mutations that now turn a test red, and the test deleted for surviving one of them; why the request carries a bare count of Minor Units |
| [14 — requesting a Transfer over HTTP](14-request-transfer-endpoint.md) | ticket 13's cross-currency hole reversed, and the half of its argument that was false; refusal handlers on the controller rather than an advice that has to win a sort — measured as `422` turning into `500`; `422` over `404` because this API already spends `404` on the path; a required header shaped to fail like a body field |
| [15 — reading Transfers back](15-list-transfers-endpoints.md) | the read side split onto its own repository so that "only `FundsReservation` writes a Transfer" stays a rule and not a list of exemptions; `404` for an unknown Transfer is ticket 05's `404` and not a second meaning; a `PATH` constant that cannot be private, measured; `?status=` with no value is no filter |
| [16 — the idempotency record](16-idempotent-execution.md) | why the key cannot be the primary key; `MANDATORY` for the `SUCCEEDED` flip; the lock `REQUIRES_NEW` costs; §4's crash window corrected |
| [17 — what a duplicate request gets back](17-duplicate-resolution.md) | the port's fourth parameter, and why the stored response forces it; phase two left out rather than guessed at; a `TransactionTemplate` because `markFailed` has to be visibly *after*; an absent row rethrown instead of matching a constraint name; the payload hashed from the parsed request, not the bytes; the one race left to ticket 18, named rather than faked |
| [19 — the check ledger and the policy](19-check-ledger-and-policy.md) | an unanswered Check has no Verdict, against §8's three-valued sketch; why `decide` refuses an empty ledger rather than settling it; a constraint test that was green for the wrong reason |
| [20 — recording a Verdict, and settlement](20-record-verdict.md) | the conditional update §8 names is not what makes concurrent Verdicts safe — the row lock is, and the mutation table records the guard no test can see; why a late Verdict needs a refusal of its own; the rule that had to split creating a Transfer from reading one |
| [24 — the stand-in FX provider](24-mock-fx-provider.md) | §28's scoped advice named a package package-by-feature never creates, and what a controller-local handler buys instead; the published OpenAPI document as a sixth cross-cutting layer, and the only leak that outlives the process; a filter exclusion with no filter to exclude, deferred rather than invented; rates from a pivot so cross rates cannot disagree |
| [27 — the transactional outbox](27-outbox-and-poller.md) | a second public type that is not a second port, so §30's three ports still stand; `MANDATORY`, because "the event commits with the change" is otherwise green for the wrong reason; publish-then-mark as the only one of three orderings that is at-least-once; Boot 4 auto-configures Jackson 3 while Jackson 2 waits on the classpath under the same simple name |
| [31 — frontend toolchain and shell](31-frontend-toolchain.md) | the generated route tree is committed *and* regenerated by the build; the SSE trap measured and named as gzip; `strict` is no longer in the Vite template |
| [32 — generated API types](32-openapi-type-generation.md) | springdoc publishes an API that never fails and whose responses are all optional — both closed on the backend; `nullable: true` is inert in OpenAPI 3.1; this generated file's diff is deliberately *not* hidden |
| [33 — the money format module](33-money-format-module.md) | a formatted amount has to read back into the field it came from, which rules out `Intl` currency formatting; neither direction divides by a hundred; the safe-integer ceiling a `long` count meets in the browser |
| [34 — the problem document module](34-problem-document-module.md) | the operator reads the module's words, not the document's; `retryable` advises a person while `retry.ts` rules a machine, and they disagree on one `409`; the URN-only rule made mechanical by a test |
| [35 — the Idempotency Key module](35-idempotency-key-module.md) | an intent is its payload, which makes `idempotency-key-reused` unreachable from this client rather than handled; "a failure does not reset" as a missing method; why the payload comparison may only ever be wrong in one direction |
| [36 — the stream event module](36-sse-event-module.md) | §21's key shape corrected — a list key that was the prefix of every Transfer page, measured; the function takes the frame, so parsing cannot fail anywhere else; the three event names written ahead of the backend that must emit them |
| [37 — the browser test harness](37-playwright-harness.md) | one route handler over a mutable answer table, so "truth changes" is a map overwrite rather than a question about handler precedence; `**/api/**` also matches the app's own `src/api/` modules and the page never boots — reproduced; the fake source departs from `EventSource` in exactly one place, and that is the point; the generated types regenerated here, and the four messages it cost |

## The initial decisions, section by section

| § | Decision | Tickets that touched it |
|---|---|---|
| §1 | [`Account` is the atomic entity — no `User`](00-initial-decisions.md#1-account-is-the-atomic-entity--no-user) | [08](08-account-entity.md) |
| §2 | [Authentication is stubbed, not built](00-initial-decisions.md#2-authentication-is-stubbed-not-built) | [04](04-security-chain-cors.md) |
| §3 | [Idempotency lives in the service layer, behind a seam](00-initial-decisions.md#3-idempotency-lives-in-the-service-layer-behind-a-seam) | [16](16-idempotent-execution.md), [17](17-duplicate-resolution.md) |
| §4 | [Request handling is three phases](00-initial-decisions.md#4-request-handling-is-three-phases) | [13](13-reserve-funds.md), [14](14-request-transfer-endpoint.md), [16](16-idempotent-execution.md), [17](17-duplicate-resolution.md) |
| §5 | [Duplicate resolution](00-initial-decisions.md#5-duplicate-resolution) | [14](14-request-transfer-endpoint.md), [16](16-idempotent-execution.md), [17](17-duplicate-resolution.md), [35](35-idempotency-key-module.md) |
| §6 | [Concurrency: pessimistic locks in a deterministic order](00-initial-decisions.md#6-concurrency-pessimistic-locks-in-a-deterministic-order) | [12](12-ordered-account-locking.md), [13](13-reserve-funds.md), [14](14-request-transfer-endpoint.md), [20](20-record-verdict.md) |
| §7 | [Transfers have an asynchronous lifecycle](00-initial-decisions.md#7-transfers-have-an-asynchronous-lifecycle) | [11](11-transfer-entity.md), [20](20-record-verdict.md) |
| §8 | [Orchestration, driven by a per-transfer check ledger](00-initial-decisions.md#8-orchestration-driven-by-a-per-transfer-check-ledger) | [19](19-check-ledger-and-policy.md), [20](20-record-verdict.md) |
| §9 | [Verdicts arrive by inbound HTTP callback](00-initial-decisions.md#9-verdicts-arrive-by-inbound-http-callback) | — |
| §10 | [Internal endpoints sit behind a shared secret](00-initial-decisions.md#10-internal-endpoints-sit-behind-a-shared-secret) | — |
| §11 | [Check services are pushed to, via the outbox](00-initial-decisions.md#11-check-services-are-pushed-to-via-the-outbox) | [27](27-outbox-and-poller.md) |
| §12 | [Hand-rolled transactional outbox](00-initial-decisions.md#12-hand-rolled-transactional-outbox) | [27](27-outbox-and-poller.md) |
| §13 | [Balances: two fields on `Account`](00-initial-decisions.md#13-balances-two-fields-on-account) | [08](08-account-entity.md), [10](10-list-accounts-and-seed.md), [13](13-reserve-funds.md), [20](20-record-verdict.md) |
| §14 | [Unanswered checks expire](00-initial-decisions.md#14-unanswered-checks-expire) | [27](27-outbox-and-poller.md) |
| §15 | [The FX rate is locked at request time](00-initial-decisions.md#15-the-fx-rate-is-locked-at-request-time) | — |
| §16 | [Money is a `long` count of minor units](00-initial-decisions.md#16-money-is-a-long-count-of-minor-units) | [01](01-project-skeleton.md), [06](06-money-and-currency.md), [07](07-conversion-function.md), [09](09-create-account-endpoint.md), [14](14-request-transfer-endpoint.md), [33](33-money-format-module.md) |
| §17 | [One event stream, thin events, no catch-up](00-initial-decisions.md#17-one-event-stream-thin-events-no-catch-up) | [36](36-sse-event-module.md) |
| §18 | [Errors are RFC 9457 `ProblemDetail`](00-initial-decisions.md#18-errors-are-rfc-9457-problemdetail) | [05](05-problem-detail-contract.md), [09](09-create-account-endpoint.md), [14](14-request-transfer-endpoint.md), [17](17-duplicate-resolution.md), [32](32-openapi-type-generation.md), [34](34-problem-document-module.md) |
| §19 | [The Transactions list shows every status](00-initial-decisions.md#19-the-transactions-list-shows-every-status) | [15](15-list-transfers-endpoints.md) |
| §20 | [No meta-framework: Vite + React, two processes](00-initial-decisions.md#20-no-meta-framework-vite--react-two-processes) | [31](31-frontend-toolchain.md) |
| §21 | [TanStack Router, and TanStack Query owns server state](00-initial-decisions.md#21-tanstack-router-and-tanstack-query-owns-server-state) | [31](31-frontend-toolchain.md), [36](36-sse-event-module.md) |
| §22 | [TanStack Form + zod, and no global state library](00-initial-decisions.md#22-tanstack-form--zod-and-no-global-state-library) | [14](14-request-transfer-endpoint.md) |
| §23 | [Bootstrap 5, CSS only, plus CSS Modules](00-initial-decisions.md#23-bootstrap-5-css-only-plus-css-modules) | [31](31-frontend-toolchain.md) |
| §24 | [Frontend testing: extract logic, unit test it, Playwright end-to-mock](00-initial-decisions.md#24-frontend-testing-extract-logic-unit-test-it-playwright-end-to-mock) | [31](31-frontend-toolchain.md), [32](32-openapi-type-generation.md), [33](33-money-format-module.md), [34](34-problem-document-module.md), [35](35-idempotency-key-module.md), [36](36-sse-event-module.md), [37](37-playwright-harness.md) |
| §25 | [Backend tests run on H2](00-initial-decisions.md#25-backend-tests-run-on-h2) | [01](01-project-skeleton.md), [10](10-list-accounts-and-seed.md) |
| §26 | [Frontend API types are generated from OpenAPI](00-initial-decisions.md#26-frontend-api-types-are-generated-from-openapi) | [01](01-project-skeleton.md), [32](32-openapi-type-generation.md), [37](37-playwright-harness.md) |
| §27 | [The flaky FX provider: timeouts, retry, then fail to the caller](00-initial-decisions.md#27-the-flaky-fx-provider-timeouts-retry-then-fail-to-the-caller) | — |
| §28 | [The mock FX provider is a real HTTP endpoint inside the app](00-initial-decisions.md#28-the-mock-fx-provider-is-a-real-http-endpoint-inside-the-app) | [24](24-mock-fx-provider.md) |
| §29 | [Maven, Flyway, and `ddl-auto=validate`](00-initial-decisions.md#29-maven-flyway-and-ddl-autovalidate) | [01](01-project-skeleton.md), [02](02-flyway-wiring.md), [06](06-money-and-currency.md), [08](08-account-entity.md), [10](10-list-accounts-and-seed.md), [11](11-transfer-entity.md), [16](16-idempotent-execution.md) |
| §30 | [Package-by-feature](00-initial-decisions.md#30-package-by-feature) | [03](03-package-skeleton-archunit.md), [07](07-conversion-function.md), [09](09-create-account-endpoint.md), [10](10-list-accounts-and-seed.md), [11](11-transfer-entity.md), [12](12-ordered-account-locking.md), [16](16-idempotent-execution.md), [24](24-mock-fx-provider.md), [27](27-outbox-and-poller.md) |
| §31 | [Residual API decisions](00-initial-decisions.md#31-residual-api-decisions) | [09](09-create-account-endpoint.md), [10](10-list-accounts-and-seed.md), [15](15-list-transfers-endpoints.md) |

Sections with no ticket against them have not been revisited since they were
written — either their work has not started, or it raised nothing the design did
not already say.
