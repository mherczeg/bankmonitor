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

## The initial decisions, section by section

| § | Decision | Tickets that touched it |
|---|---|---|
| §1 | [`Account` is the atomic entity — no `User`](00-initial-decisions.md#1-account-is-the-atomic-entity--no-user) | — |
| §2 | [Authentication is stubbed, not built](00-initial-decisions.md#2-authentication-is-stubbed-not-built) | [04](04-security-chain-cors.md) |
| §3 | [Idempotency lives in the service layer, behind a seam](00-initial-decisions.md#3-idempotency-lives-in-the-service-layer-behind-a-seam) | — |
| §4 | [Request handling is three phases](00-initial-decisions.md#4-request-handling-is-three-phases) | — |
| §5 | [Duplicate resolution](00-initial-decisions.md#5-duplicate-resolution) | — |
| §6 | [Concurrency: pessimistic locks in a deterministic order](00-initial-decisions.md#6-concurrency-pessimistic-locks-in-a-deterministic-order) | — |
| §7 | [Transfers have an asynchronous lifecycle](00-initial-decisions.md#7-transfers-have-an-asynchronous-lifecycle) | — |
| §8 | [Orchestration, driven by a per-transfer check ledger](00-initial-decisions.md#8-orchestration-driven-by-a-per-transfer-check-ledger) | — |
| §9 | [Verdicts arrive by inbound HTTP callback](00-initial-decisions.md#9-verdicts-arrive-by-inbound-http-callback) | — |
| §10 | [Internal endpoints sit behind a shared secret](00-initial-decisions.md#10-internal-endpoints-sit-behind-a-shared-secret) | — |
| §11 | [Check services are pushed to, via the outbox](00-initial-decisions.md#11-check-services-are-pushed-to-via-the-outbox) | — |
| §12 | [Hand-rolled transactional outbox](00-initial-decisions.md#12-hand-rolled-transactional-outbox) | — |
| §13 | [Balances: two fields on `Account`](00-initial-decisions.md#13-balances-two-fields-on-account) | — |
| §14 | [Unanswered checks expire](00-initial-decisions.md#14-unanswered-checks-expire) | — |
| §15 | [The FX rate is locked at request time](00-initial-decisions.md#15-the-fx-rate-is-locked-at-request-time) | — |
| §16 | [Money is a `long` count of minor units](00-initial-decisions.md#16-money-is-a-long-count-of-minor-units) | [01](01-project-skeleton.md), [06](06-money-and-currency.md), [07](07-conversion-function.md) |
| §17 | [One event stream, thin events, no catch-up](00-initial-decisions.md#17-one-event-stream-thin-events-no-catch-up) | — |
| §18 | [Errors are RFC 9457 `ProblemDetail`](00-initial-decisions.md#18-errors-are-rfc-9457-problemdetail) | [05](05-problem-detail-contract.md) |
| §19 | [The Transactions list shows every status](00-initial-decisions.md#19-the-transactions-list-shows-every-status) | — |
| §20 | [No meta-framework: Vite + React, two processes](00-initial-decisions.md#20-no-meta-framework-vite--react-two-processes) | — |
| §21 | [TanStack Router, and TanStack Query owns server state](00-initial-decisions.md#21-tanstack-router-and-tanstack-query-owns-server-state) | — |
| §22 | [TanStack Form + zod, and no global state library](00-initial-decisions.md#22-tanstack-form--zod-and-no-global-state-library) | — |
| §23 | [Bootstrap 5, CSS only, plus CSS Modules](00-initial-decisions.md#23-bootstrap-5-css-only-plus-css-modules) | — |
| §24 | [Frontend testing: extract logic, unit test it, Playwright end-to-mock](00-initial-decisions.md#24-frontend-testing-extract-logic-unit-test-it-playwright-end-to-mock) | — |
| §25 | [Backend tests run on H2](00-initial-decisions.md#25-backend-tests-run-on-h2) | [01](01-project-skeleton.md) |
| §26 | [Frontend API types are generated from OpenAPI](00-initial-decisions.md#26-frontend-api-types-are-generated-from-openapi) | [01](01-project-skeleton.md) |
| §27 | [The flaky FX provider: timeouts, retry, then fail to the caller](00-initial-decisions.md#27-the-flaky-fx-provider-timeouts-retry-then-fail-to-the-caller) | — |
| §28 | [The mock FX provider is a real HTTP endpoint inside the app](00-initial-decisions.md#28-the-mock-fx-provider-is-a-real-http-endpoint-inside-the-app) | — |
| §29 | [Maven, Flyway, and `ddl-auto=validate`](00-initial-decisions.md#29-maven-flyway-and-ddl-autovalidate) | [01](01-project-skeleton.md), [02](02-flyway-wiring.md), [06](06-money-and-currency.md) |
| §30 | [Package-by-feature](00-initial-decisions.md#30-package-by-feature) | [03](03-package-skeleton-archunit.md), [07](07-conversion-function.md) |
| §31 | [Residual API decisions](00-initial-decisions.md#31-residual-api-decisions) | — |

Sections with no ticket against them have not been revisited since they were
written — either their work has not started, or it raised nothing the design did
not already say.
