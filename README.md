# Global Payment Service

A payment gateway that holds account balances and moves money between accounts, with
currency conversion, idempotent retries, and downstream notification of other domain
services.

> **This README is a stub.** It exists so the work can be verified while it is being
> built. The full write-up — architecture, decisions and rejections, edge cases, the
> TODO list and production readiness — is the last ticket in the plan, and will replace
> this file. Until then the reasoning lives in [`docs/design-decisions.md`](docs/design-decisions.md),
> the vocabulary in [`CONTEXT.md`](CONTEXT.md), and the plan in
> [`.scratch/global-payment-service/`](.scratch/global-payment-service/).

## Requirements

**A JDK 21 or newer, and nothing else.** No Docker, no database to install, no Maven —
`./mvnw` downloads the version it needs on first run.

```bash
java -version   # must be 21+
```

The frontend is not built yet. When it arrives it will need Node (see `.nvmrc`) and run
as a second process.

## Run the tests

```bash
./mvnw test
```

Expect **17 passing tests** and no Docker daemon involved. The suite runs on an in-memory
H2 database; Testcontainers was rejected precisely so this command works on a clean
machine.

## Run the application

```bash
./mvnw spring-boot:run
```

It starts on **http://localhost:8080**.

| Endpoint | What it is |
|---|---|
| [`/actuator/health`](http://localhost:8080/actuator/health) | Health check, including H2 connectivity |
| [`/v3/api-docs`](http://localhost:8080/v3/api-docs) | The OpenAPI document the frontend's types are generated from |
| [`/swagger-ui/index.html`](http://localhost:8080/swagger-ui/index.html) | Browsable API |

Quick check from a second terminal:

```bash
curl -s localhost:8080/actuator/health
# {"status":"UP","components":{"db":{"status":"UP","details":{"database":"H2",...
```

There is no business API yet — `/v3/api-docs` currently reports zero paths, which is
correct for the current state of the build.

## What is built so far

Tickets 01–03 of 44: the skeleton, schema management, the package structure the domain
code will be written into, and the two ecosystem bets that had to be settled first.
**Both bets won.**

1. **Hibernate maps a Java `record` as `@Embeddable`.** `Money` and `Account` are both
   records by design; if Hibernate could not instantiate one through its canonical
   constructor, every entity's shape would have changed.
2. **`springdoc-openapi` works on Spring Boot 4** — on the 3.x line only; 2.x targets
   Boot 3. The generated frontend types depend on this, and so does the browser test
   strategy that rests on them.

Both are proved by tests in the repo rather than by reading changelogs:

```bash
./mvnw test -Dtest='*SpikeTest'
```

**Flyway owns the schema** and Hibernate runs on `ddl-auto=validate`, from before the
first table exists rather than baselined at the end. There are no migrations yet — each
slice ships the one for the table it introduces, a convention written down in
[`src/main/resources/db/migration/README.md`](src/main/resources/db/migration/README.md).
What is already load-bearing is that an entity with no table behind it fails startup
naming the table Hibernate went looking for, which `FlywayOwnsTheSchemaTest` proves by
booting the application with exactly that mistake in it.

**Virtual threads are on** (`spring.threads.virtual.enabled=true`), and they are
load-bearing rather than a nicety. The stand-in Exchange Rate provider is a real HTTP
endpoint inside this same application, so serving a transfer means one request thread
waits on a second thread of the same server. On a bounded pool that can deadlock against
itself; virtual threads are not scarce, which is also why the design declines a circuit
breaker. `ApplicationBootsTest` asks the running container what kind of thread served the
request rather than asserting the property is set — flip the flag off and it fails.

## Module boundaries

The code is organised **by feature, not by layer** — one package per slice of the domain,
each documented by its own `package-info.java`:

```
hu.bankmonitor.payments
├── accounts/      the Account entity, its endpoints, and its Reserved Amount
├── transfers/     the Transfer lifecycle — the only package that depends on the others
│   └── checks/    the Check Ledger, and the /internal endpoint Verdicts arrive on
├── idempotency/   run-once-per-key replay protection      · one public port
├── fx/            exchange rates from an unreliable provider · one public port
├── outbox/        events written in the same transaction as the change · one public port
├── mockfx/        the stand-in Exchange Rate provider — depends on nothing
└── common/        Money, Currency, problem types — depended on by everything
```

Dependencies run one way: `transfers` onto `accounts`, `fx`, `idempotency` and `outbox`,
and nothing points back. Three public ports in total.

**Most of that boundary is enforced by the compiler, not by review.** Java's default
access level is package-private, so a repository declared with no modifier is literally
uncallable from another package — crossing the boundary does not compile.
`ModuleBoundariesHoldTest` covers the two things the compiler cannot see: that the slices
are free of cycles, and that no controller holds a repository — the latter matching on
role as well as on name, because a `@RestController` called `AccountEndpoint` is the same
mistake and a suffix rule would wave it through. Both rules are also run against fixtures
that break them on purpose, so a rule that quietly stopped matching anything would show up
as a failure rather than as a green tick.

One trap this arrangement sets, noted where it will be hit: `@Transactional` on a
non-public method is **silently ignored** under proxy-based AOP. The *class* may be
package-private; the `@Transactional` *method* stays public.

## Layout

```
pom.xml, src/               the Spring Boot application
frontend/                   the React app (not yet created)
CONTEXT.md                  domain vocabulary — the words this codebase uses
docs/design-decisions.md    what was chosen, why, and what was rejected
docs/deferred.md            what was consciously left out, and what it would take
docs/adr/                   the one decision that shapes the others
.scratch/                   the spec and all 44 tickets
session-logs/               the AI sessions that produced the plan
```
