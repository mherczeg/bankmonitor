# Global Payment Service

A payment gateway that holds account balances and moves money between accounts, with
currency conversion, idempotent retries, and downstream notification of other domain
services.

> **This README is a stub.** It exists so the work can be verified while it is being
> built. The full write-up — architecture, decisions and rejections, edge cases, the
> TODO list and production readiness — is the last ticket in the plan, and will replace
> this file. Until then the reasoning lives in [`docs/design-decisions/`](docs/design-decisions/),
> the vocabulary in [`CONTEXT.md`](CONTEXT.md), and the plan in
> [`.scratch/global-payment-service/`](.scratch/global-payment-service/).

## Requirements

**A JDK 21 or newer** for the backend, and no Docker, no database to install and no
Maven — `./mvnw` downloads the version it needs on first run.

```bash
java -version   # must be 21+
```

**Node for the frontend**, at the version in [`.nvmrc`](.nvmrc). It runs as a second
process, which is the price of not adding a second production runtime beside the JVM;
see [`frontend/README.md`](frontend/README.md).

## Run the tests

```bash
./mvnw test               # backend
cd frontend && npm test   # frontend
```

Expect **106 passing backend tests** and **6 in the frontend**, with no Docker daemon
involved. The backend suite runs on an in-memory H2 database; Testcontainers was rejected
precisely so this command works on a clean machine.

## Run the application

Two processes, in two terminals.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # http://localhost:8080
```

```bash
cd frontend && npm install && npm run dev               # http://localhost:5173
```

**http://localhost:5173 is the application**; the backend on 8080 is what it talks to,
and the dev server proxies `/api` and `/internal` there so the browser stays on one
origin. [`frontend/README.md`](frontend/README.md) has the frontend's own details.

**The `dev` profile is what puts demo accounts in the database**, and it is opt-in on
purpose. Without the flag the application starts empty — which is what the test suite gets,
and what a deployment would get. Demo rows are seeded by a `@Profile("dev")` startup runner
rather than by a migration, because a migration runs everywhere the schema does, and every
test that reads the accounts table would then start from someone else's fixtures.

| Endpoint | What it is |
|---|---|
| [`GET /api/accounts`](http://localhost:8080/api/accounts) | Every account, with its balance and its Available Balance |
| `POST /api/accounts` | Opens an Account in a given Currency with a starting balance |
| [`/actuator/health`](http://localhost:8080/actuator/health) | Health check, including H2 connectivity |
| [`/v3/api-docs`](http://localhost:8080/v3/api-docs) | The OpenAPI document the frontend's types are generated from |
| [`/swagger-ui/index.html`](http://localhost:8080/swagger-ui/index.html) | Browsable API |

Quick check from a second terminal:

```console
$ curl -s localhost:8080/actuator/health
{"status":"UP","components":{"db":{"status":"UP","details":{"database":"H2",...

$ curl -s localhost:8080/api/accounts
[{"id":1,"currency":"EUR","balanceMinorUnits":250000,
  "reservedAmountMinorUnits":0,"availableBalanceMinorUnits":250000},
 ...
 {"id":5,"currency":"HUF","balanceMinorUnits":250000,
  "reservedAmountMinorUnits":0,"availableBalanceMinorUnits":250000}]

$ curl -s -X POST localhost:8080/api/accounts -H 'Content-Type: application/json' \
    -d '{"currency":"EUR","openingBalanceMinorUnits":10050}'
{"id":6,"currency":"EUR","balanceMinorUnits":10050,
 "reservedAmountMinorUnits":0,"availableBalanceMinorUnits":10050}
```

Those two `250000`s are the same number and not the same amount — €2,500.00 and 250,000 Ft.
That is the point of the `MinorUnits` suffix, and the reason the demo set includes HUF.

That is the whole business API so far; the rest of `/v3/api-docs` is still ahead of the
build.

## What is built so far

Tickets 01–13, 16 and 31–34 of 44: the skeleton, schema management, the package structure
the domain code will be written into, the security chain in front of it, the error contract
every endpoint will answer with, the value type every amount in the system is expressed in
and the single conversion between currencies, the first entity and the first table, the
first endpoint that writes to it and the first that reads it back, the Transfer and the
locking rule the concurrency design rests on, the reservation that rule protects, the claim
on an Idempotency Key, the frontend's shell, the generated API types that join the two
halves, the frontend edge that turns Minor Units into decimals, the reading an operator
gets of a failed request, and the two ecosystem bets that had to be settled first.
**Both bets won.**

1. **Hibernate maps a Java `record` as `@Embeddable`.** `Money` is a record by design; if
   Hibernate could not instantiate one through its canonical constructor, every value type
   in the system would have changed shape. The spike that settled it has since been
   overtaken by the real thing — `Money` is now mapped against a hand-written migration,
   on the same `validate` setting the application runs on. The support stops at
   `@Embeddable`: an `@Entity` record compiles, boots and passes `validate`, then throws on
   the first write because its components are `final`, which is why `Account` is a class.
2. **`springdoc-openapi` works on Spring Boot 4** — on the 3.x line only; 2.x targets
   Boot 3. The generated frontend types depend on this, and so does the browser test
   strategy that rests on them.

Both are proved by tests in the repo rather than by reading changelogs:

```bash
./mvnw test -Dtest='*SpikeTest'
```

**Money is a whole count of Minor Units — a `long` of fillér or cents — paired with the
currency it is denominated in.** A number on its own is not money, so the two are one
value rather than a column beside a column, and arithmetic on it refuses operands in
differing currencies. `BigDecimal` was rejected not for drift (it is exact) but because it
is unconstrained — nothing stops a fraction of a fillér being representable — and because
its `equals` compares scale, so the same amount written two ways compares unequal. The
per-currency decimal places (2 for EUR and USD, 0 for HUF) are read at the edges that
parse and display an amount, and nowhere else: **the core never divides by a hundred.** On
the browser side that edge is a single module, `frontend/src/money.ts`, whose two functions
are exact inverses because the string it renders is also what an operator edits in a form
field — which is why it renders `100.50` and not `€100.50`.

**Flyway owns the schema** and Hibernate runs on `ddl-auto=validate`, from before the
first table existed rather than baselined at the end. Each slice ships the migration for
the table it introduces — `V1__accounts.sql` is the first — a convention written down in
[`src/main/resources/db/migration/README.md`](src/main/resources/db/migration/README.md).
An entity with no table behind it fails startup naming the table Hibernate went looking
for, which `FlywayOwnsTheSchemaTest` proves by booting the application with exactly that
mistake in it.

**An Account holds two figures, not one:** its balance, and the Reserved Amount committed
to transfers that have not finished. Their difference is the Available Balance, which is
what an overdraft check tests against — so an account's own row answers "how much of this
is still spendable" without reading a single transfer. Both are `Money`, so the table
carries four columns and a check constraint that the two agree on their currency, which is
fixed when the account is opened. A second constraint keeps the Reserved Amount between
zero and the balance: the overdraft refusal itself belongs in the service, under the lock,
where it can reach the caller as a `422`, and this is what makes a route around it a
failed write rather than an overdrawn account.

**`POST /api/accounts` opens one, and amounts cross the wire as an integer count of Minor
Units in a field whose name says so** — `openingBalanceMinorUnits`, never
`openingBalance`. `10050` in a field called `openingBalance` reads as ten thousand and
fifty euros to one caller and as a hundred euros fifty to the next; the suffix leaves one
reading, and the decimal form an operator types belongs to the form, which converts it
before it leaves the browser. The name alone is not the whole guard: **Jackson's default
is to truncate a decimal into an integer field**, so `100.50` would have opened a euro
account holding 100 cents with a `201` and no warning. `spring.jackson.deserialization.accept-float-as-int=false`
makes it a rejected field instead, and the measured before-and-after is in
[the ticket's design record](docs/design-decisions/09-create-account-endpoint.md).

The currency crosses as the `Currency` enum rather than a string, so the three
denominations this service can quote are a closed set in the OpenAPI document too and the
frontend picks the same list up rather than restating it. The cost is that Jackson refuses
an unknown name *before* Bean Validation runs, which would have made a bad currency an
unreadable-body error with no field in it; the error contract below now reports a body
Jackson rejected **at a member** as a validation failure naming that member. The response
carries all three balance figures and no `Location` header — there is no single-Account
resource for one to address, and the body carries the identifier. Both are in
[`docs/deferred.md`](docs/deferred.md).

**`GET /api/accounts` is the first business endpoint**, and it reports three figures from
two stored ones: the Available Balance is subtracted on the way out, never persisted, so no
write can leave it disagreeing with the balance and the Reserved Amount it comes from. It is
sent rather than left for the client to compute because it is the figure the overdraft check
tests against — a client that subtracted for itself would be a second implementation of the
rule, and the one that drifts. The listing is ordered, which is a contract rather than a
detail of the query: an unordered one would let the accounts screen reshuffle itself between
two refetches of unchanged data. It is also the first shape in `/v3/api-docs`, so from here
on a renamed field is a TypeScript compile error in the frontend rather than an
`undefined` at runtime.

Every seeded account starts with nothing reserved, because reserving is what a Transfer
does and transfers do not exist yet — so on a fresh `dev` start, Available Balance equals
balance everywhere. That is the derivation working, not the demo data being flat.

**The two Accounts a transfer touches are locked in ascending ID order, never by their
role in the transfer.** Each is taken with its own `SELECT … FOR UPDATE`, in a loop over
the sorted IDs. Were the locks taken by role, a transfer 5 → 9 would hold 5 and want 9
while a simultaneous 9 → 5 held 9 and wanted 5, and neither could give way; ordering by ID
makes both contend for 5 first, so the loser waits holding nothing and there is no cycle
to form. Deadlock is structurally impossible rather than merely unlikely. The order is
taken by this code rather than left to a single ordered query, so it is not the query
planner's to change. The operation refuses to run outside a transaction
(`@Transactional(propagation = MANDATORY)`), since a lock released before the balance it
guards is checked would leave a suite that passes and a race in production. It is viable
only because the Exchange Rate is fetched in a phase of its own before any of this: a
transaction holding row locks must never be waiting on a slow provider, and an ArchUnit
rule walks everything reachable from the locking operation to keep it that way.

**Virtual threads are on** (`spring.threads.virtual.enabled=true`), and they are
load-bearing rather than a nicety. The stand-in Exchange Rate provider is a real HTTP
endpoint inside this same application, so serving a transfer means one request thread
waits on a second thread of the same server. On a bounded pool that can deadlock against
itself; virtual threads are not scarce, which is also why the design declines a circuit
breaker. `ApplicationBootsTest` asks the running container what kind of thread served the
request rather than asserting the property is set — flip the flag off and it fails.

## Security: configured, not disabled

**There is no authentication, and that is a decision rather than a gap.** No requirement
in the task references a caller: transfers move money between bare account IDs and all
three screens are unscoped, so there is no principal to model and no controller signature
carries one. What *is* here is a Spring Security filter chain in which every setting is
one someone can defend.

The chain **denies by default** and names what it opens — the public API under `/api/**`,
the health endpoint, and the OpenAPI document and its UI. That matters less for today's
application than for the next one: under a blanket `permitAll`, a new prefix is reachable
the moment its controller is written, and stays reachable if its rule is later deleted.
The one real authorization rule — a shared secret on the `/internal/**` endpoint that
receives Check verdicts — lands with the endpoint it protects, because until then there
is nothing to protect.

The rest of the chain, and why each is what it is:

| Setting | Why |
|---|---|
| CSRF **off** | The API is stateless JSON with no cookie and no `Authorization` header, and the CORS policy sends no credentials. A CSRF token defends ambient authority; there is none here to borrow. |
| Sessions **stateless** | Nothing is remembered between requests, so a session would be state with no reader. Measured by the absence of a `Set-Cookie`, not by reading the setting back. |
| CORS **on, for `/api/**` only** | The frontend runs as its own process on its own origin. The mock FX provider is deliberately outside the mapping — it stands in for a third party reached server-to-server. |
| Origins, methods and headers **enumerated** | `payments.cors.allowed-origins` defaults to the Vite dev server; a deployment serving both from one origin sets its own. Wildcards would make the policy unreadable as a statement of intent. |
| `Retry-After` **exposed** | Only CORS-safelisted response headers reach cross-origin JavaScript. The error contract puts the retry policy in this header, and without naming it the frontend reads it as absent — in the browser only, while `curl` shows it present. |
| Credentials **off** | This is what keeps CSRF-off safe: no cookie or credential rides along on a cross-origin call. |

Two traps are worth knowing about, because both fail quietly:

1. **Security runs before Spring MVC and answers the CORS preflight itself.** It needs the
   CORS entry on the chain *and* a `CorsConfigurationSource` bean; a `@CrossOrigin`
   annotation on a controller is never reached. The symptom is an opaque browser failure
   that `curl` cannot reproduce.
2. **The authorization filter also runs on the `ERROR` dispatch.** With deny-by-default,
   forgetting to permit it turns every 404 and 405 under a permitted path into an empty
   403 — a plausible-looking status that hides the real one.

`SecurityChainTest` asserts each of these through a running server, as an effect a caller
can observe: a status code, a header, a cookie that is not set. A test that asserted the
configuration methods had been called would only restate the source file.

## Errors: one document, one discriminator

**Every error this API emits is an RFC 9457 problem document**
(`application/problem+json`), and a client branches on exactly one field: the `type` URN.
Not the status code, and not a second `code` field beside the URN — two discriminators
drift, and eventually one of them lies. The two `409`s make that concrete: the same
status, opposite advice about retrying, told apart by their URN alone.

```console
$ curl -i localhost:8080/api/nope
HTTP/1.1 404
Content-Type: application/problem+json

{"type":"urn:problem:not-found","title":"Not Found",
 "status":404,"detail":"This API has no endpoint at that path.","instance":"/api/nope"}
```

The URNs live in one place — the `ProblemType` enum in `common` — and reach the frontend
through the OpenAPI document, so the two runtimes share one vocabulary and a URN that stops
being emitted becomes a type error rather than a silently dead branch in the client. The
next section is how.

The implementation is Spring's own `ResponseEntityExceptionHandler`, not a hand-rolled
envelope. Spring already answers `@Valid` rejections, `415`, `405` and malformed JSON
with problem documents *before* any controller code runs, so a custom shape would not
replace those responses — it would ship a second error format beside one that cannot be
switched off. `ProblemDocumentAdvice` adopts them and adds five things:

| Addition | Why |
|---|---|
| A `type` URN on every response | The framework leaves `type` as `about:blank`, which forces a client back onto branching on the status code. A document that already names its type — a refusal a slice raised itself — keeps it, and a client error this API does not name more precisely gets `urn:problem:client-error` rather than the nearest-looking URN. |
| `errors: [{ field, message }]` on a validation failure | The default packs every violation into one sentence in `detail`, which no form can mark up against the input that caused it. A rejected parameter carries the member too, so the shape does not depend on whether the bad value arrived in the body. Sorted, because Bean Validation promises no order. |
| A `500` document for anything unhandled | Otherwise the request leaves the dispatcher for Boot's `/error` page — a different shape, derived from the exception, saying more about this server than a caller should learn. Its `detail` is a fixed sentence. |
| `Retry-After` where retrying will help, and nowhere else | The retryable case is then machine-readably marked as such: the in-progress `409` and the exhausted FX provider's `503` carry it; the key-reuse `409` never does. |
| The same `errors` breakdown for a value the **deserializer** refused | An unknown enum name or a decimal in a whole-number field fails before any constraint runs, and Spring reports every unreadable body as `urn:problem:malformed-request` with nothing in it. A failure carrying a *path* — a location inside the document — is a well-formed request with one bad member, and is reported as a validation failure naming it. JSON that does not parse has no path and stays malformed. One caveat the client can see: Jackson stops at the first such member, so this list holds one entry where a constraint failure holds every violation. |

`ProblemDocumentContractTest` runs the whole contract at the web layer with no database
behind it, putting every case through one assertion helper — which is what makes "a
caller that has parsed one problem document has parsed all of them" a checked claim
rather than an intention.

**One error does not pass through here:** a refusal from the security filter chain is
raised before the dispatcher and answered with an empty body. Nothing is denied on
purpose yet; see the TODO list.

**The client half of the rule is `frontend/src/api/problem.ts`**, which maps a URN to a
title, a body and whether retrying will help — and reads no other member of the document,
asserted against its own source rather than left as an intention. It is where the two
`409`s stop being the same news: one advises waiting and trying again, the other says a
retry can never succeed. The wording is the frontend's own, because a document's `title`
is a status reason phrase and its `detail` is written for whoever is reading the response,
neither of which an operator can act on. [The frontend README](frontend/README.md#what-an-operator-is-told-when-a-request-fails)
has the surface.

## The frontend's types are generated, not written

`/v3/api-docs` is not documentation here — it is a build input. `openapi-typescript` turns
it into `frontend/src/api/schema.gen.ts`, and every API shape the browser code touches is
an alias onto that file.

**This is what makes the browser tests worth running.** They mock the network, so every
response in them is a shape someone wrote by hand; without generated types they would prove
the frontend copes with shapes its own test author invented. With them, a mock the backend
would never send does not compile. `frontend/src/api/types.test.ts` demonstrates it on four
drifted shapes — an amount as a string, a member left out, a currency this service does not
quote, an invented problem-type URN — each pinned by a `@ts-expect-error` that fails the
build if it ever stops being an error.

Two things had to change on this side for that to be true rather than nearly true:

| In the document | Why it is not free |
|---|---|
| The `ProblemType` URNs, the problem document's shape, and a `default` response on every operation | No controller *returns* a problem document — `ProblemDocumentAdvice` does, after the handler threw — so springdoc has nothing to introspect and publishes an API that appears never to fail. `OpenApiConfiguration` contributes the three schemas, building the URN list from `ProblemType.values()` so the vocabulary still exists in exactly one place. `default` is the literal truth: any status an operation does not name is this document. |
| `required` on the response shape | springdoc derives `required` from constraint annotations, and a response is never validated — so an unannotated response record publishes a schema whose every member is optional, and a mock omitting the balance would still compile. `AccountResponse` says so itself, and `AccountSchemaReachesTheDocumentTest` compares that list against the record's own components so the two cannot drift apart. |

**Regenerating is a command, not a build step:**

```bash
cd frontend && npm run api-types    # with the backend running on 8080
```

The output is committed, which is the honest trade: a fresh clone type-checks with no
generator run and no backend, and in exchange the file can go stale if nobody regenerates
it. Nothing enforces it — there is no CI here to enforce it *in* — so the discipline is to
regenerate whenever the API moves and to **read the diff** rather than commit past it. A
change in that file is the backend's contract moving underneath four screens, and the type
errors that follow are the list of places that have to move with it. `.gitattributes` leaves
the file **un**marked as generated, for that reason — the mark is what collapses a diff in a
pull request, and this is the diff worth opening. The
residual risk, and the CI step that would close it, are in
[`docs/deferred.md`](docs/deferred.md).

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
frontend/                   the React app, with its own README
CONTEXT.md                  domain vocabulary — the words this codebase uses
docs/design-decisions/      what was chosen, why, and what was rejected
docs/deferred.md            what was consciously left out, and what it would take
docs/adr/                   the one decision that shapes the others
.scratch/                   the spec and all 44 tickets
session-logs/               the AI sessions that produced the plan
```
