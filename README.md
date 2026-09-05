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

Expect **8 passing tests** and no Docker daemon involved. The suite runs on an in-memory
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

Ticket 01 of 44: the skeleton, and the two ecosystem bets that had to be settled before
writing domain code against them. **Both won.**

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

**Virtual threads are on** (`spring.threads.virtual.enabled=true`), and they are
load-bearing rather than a nicety. The stand-in Exchange Rate provider is a real HTTP
endpoint inside this same application, so serving a transfer means one request thread
waits on a second thread of the same server. On a bounded pool that can deadlock against
itself; virtual threads are not scarce, which is also why the design declines a circuit
breaker. `ApplicationBootsTest` asks the running container what kind of thread served the
request rather than asserting the property is set — flip the flag off and it fails.

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
