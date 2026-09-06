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

Expect **320 passing backend tests** and **130 in the frontend**, with no Docker daemon
involved. The backend suite runs on an in-memory H2 database; Testcontainers was rejected
precisely so this command works on a clean machine.

## Run the application

Two processes, in two terminals.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,mock-fx   # http://localhost:8080
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

**`mock-fx` is the second profile, and it is separate from `dev` on purpose** — it switches
on the [stand-in Exchange Rate provider](#the-exchange-rate-provider-is-a-stand-in-that-misbehaves)
below. Naming both is what a full local run wants; naming only `dev` gives you demo accounts
and no rate source, which is the right shape for pointing the application at a real provider.

| Endpoint | What it is |
|---|---|
| [`GET /api/accounts`](http://localhost:8080/api/accounts) | Every account, with its balance and its Available Balance |
| `POST /api/accounts` | Opens an Account in a given Currency with a starting balance |
| `POST /api/transfers` | Requests a Transfer between two Accounts, reserving the funds on the source |
| [`GET /api/transfers`](http://localhost:8080/api/transfers) | Every Transfer in every status, newest first; `?status=` narrows it to one |
| `GET /api/transfers/{id}` | One Transfer by the identifier the `201` above returns in its `Location`, with the Check Ledger it is waiting on |
| `POST /internal/transfers/{id}/checks/{check}` | A Check service reports its Verdict — the one endpoint that takes a credential |
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

Requesting a Transfer takes an Idempotency Key header, and no Currency — the Transfer is
denominated by its source Account, so a payload that named one could contradict it:

```console
$ curl -s -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' \
    -H 'X-Idempotency-Key: 8f14e45f-ceea-467a-9c1c-7c9a5b3f2d10' \
    -d '{"fromAccountId":1,"toAccountId":2,"amountMinorUnits":10050}'
{"id":1,"fromAccountId":1,"toAccountId":2,"status":"PENDING",
 "debitedAmountMinorUnits":10050,"debitedAmountCurrency":"EUR",
 "creditedAmountMinorUnits":10050,"creditedAmountCurrency":"EUR",
 "createdAt":"2026-09-06T09:41:00Z"}
```

Nothing has moved: account 1 still holds its €2,500.00, with €100.50 of it now spoken for
and its Available Balance down to €2,399.50. The Transfer is `PENDING` until its Checks
come back.

That `201` also carries a `Location`, which is the second endpoint below — so a client can
follow the header rather than assemble the URL from the body:

```console
$ curl -si -X POST localhost:8080/api/transfers ... | grep -i '^location:'
location: /api/transfers/1

$ curl -s localhost:8080/api/transfers/1
{"id":1,"fromAccountId":1,"toAccountId":2,"status":"PENDING",...}

$ curl -s 'localhost:8080/api/transfers?status=PENDING'
[{"id":1,"fromAccountId":1,"toAccountId":2,"status":"PENDING",...}]
```

**The listing shows every status, not only the settled ones.** A `PENDING` Transfer that
appeared nowhere would tell an operator their money had vanished, which is the one thing an
asynchronous lifecycle must not do. `?status=` narrows to one; `?status=` with nothing after
it is no filter rather than an error, so a form that always submits its fields still works.
A status this domain has no name for is a `400` naming the four that exist.

**A Transfer between two Accounts in different Currencies is converted.** The Exchange Rate
is fetched when the Transfer is requested and locked onto it for its life, so the figure an
operator was shown is the figure it will settle at whatever the market does while its Checks
are outstanding — and a settled conversion can be read back and audited:

```console
$ curl -s -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' \
    -H 'X-Idempotency-Key: 3fa85f64-5717-4562-b3fc-2c963f66afa6' \
    -d '{"fromAccountId":1,"toAccountId":5,"amountMinorUnits":10050}'
{"id":1,"fromAccountId":1,"toAccountId":5,"status":"PENDING",
 "debitedAmountMinorUnits":10050,"debitedAmountCurrency":"EUR",
 "creditedAmountMinorUnits":39698,"creditedAmountCurrency":"HUF",
 "exchangeRate":395.000000,"exchangeRateFetchedAt":"2026-09-06T19:56:38.638668532Z",
 "createdAt":"2026-09-06T19:56:38.320253112Z"}
```

€100.50 leaves account 1 and 39,698 Ft arrives at account 5 — two amounts, each in its own
Account's Currency, and the rate that relates them. The two timestamps are both about the
Transfer's beginning and they are in the order the request goes through: `createdAt` is
stamped when the request arrives, and the quote comes back a few hundred milliseconds later.

**`exchangeRate` and `exchangeRateFetchedAt` are absent together or not at all**, and what
decides it is the Transfer rather than the endpoint that reported it — unlike `checks` below,
which is the one member a `GET` of a single Transfer adds. A Transfer between two Accounts
holding the same Currency asked no provider anything — a rate of `1` would claim a quote that
was never fetched — so the same-Currency response above carries neither. That is also the point:
**a same-Currency Transfer makes no call to the provider at all**, so an outage there cannot
stop one.

Two things can go wrong that belong to the conversion rather than to the Accounts:

```console
$ # one fillér into a euro Account: worth a quarter of a cent, so there is nothing to credit
$ curl -si -X POST ... -d '{"fromAccountId":4,"toAccountId":2,"amountMinorUnits":1}'
HTTP/1.1 422
{"type":"urn:problem:conversion-rounds-to-zero","title":"Unprocessable Content","status":422,
 "detail":"This amount converts to nothing in the destination Account's Currency.",
 "instance":"/api/transfers","debitedAmountMinorUnits":1,"debitedAmountCurrency":"HUF",
 "destinationCurrency":"EUR","exchangeRate":0.002532}

$ # the provider did not answer, three attempts in
$ curl -si -X POST ... -d '{"fromAccountId":1,"toAccountId":5,"amountMinorUnits":10050}'
HTTP/1.1 503
Retry-After: 5
{"type":"urn:problem:fx-provider-unavailable","title":"Service Unavailable","status":503,
 "detail":"The Exchange Rate provider did not answer, so this Transfer could not be priced.
           Retry with the same Idempotency Key.",
 "instance":"/api/transfers","baseCurrency":"EUR","quoteCurrency":"HUF","attempts":3}
```

The `422` refuses a Transfer that would debit the source and credit nothing; the remedy is a
larger amount, and the document carries every figure the comparison used so an operator can
work out how much larger.

**The `503` is the only `5xx` this API raises on purpose**, and the status is the whole
message: the request was fine, this service is fine, and a third party it depends on is not.
The `Retry-After` says coming back is worth doing, and the attempt count says the failure was
an outage rather than a blip — three attempts had already been spent inside that one request
before it answered. Its Idempotency Key is left released, so resending the identical request
under the same key executes it rather than replaying the failure.

**Sending the same request twice moves money once.** That is what the Idempotency Key is
for, and it is the whole of what a client has to do about retrying: resend the request, key
and all, and the second call is answered from what the first one left behind rather than
executed again.

```console
$ curl -s -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' \
    -H 'X-Idempotency-Key: 8f14e45f-ceea-467a-9c1c-7c9a5b3f2d10' \
    -d '{"fromAccountId":1,"toAccountId":2,"amountMinorUnits":10050}'
{"id":1,"fromAccountId":1,"toAccountId":2,"status":"PENDING",...}
```

The same `201`, the same Transfer, the same `Location` — and still one row in `transfers`,
with €100.50 reserved once. The reservation is never reached a second time; the response is
read back out of the key's record. What the key is compared against is the *parsed* request,
so the same Transfer resent with its JSON members reordered is still a retry.

**Two things can go wrong with a key, and they mean opposite things.** Both are `409`, so
the `type` URN tells them apart — and so does `Retry-After`, which is present on exactly the
one worth retrying:

```console
$ # the first request is still running
$ curl -si -X POST ... -H 'X-Idempotency-Key: 8f14e45f-ceea-467a-9c1c-7c9a5b3f2d10' ...
HTTP/1.1 409
retry-after: 1
{"type":"urn:problem:request-in-progress","title":"Conflict","status":409,
 "detail":"A request with this Idempotency Key is still being processed. Retry with the same key.",
 "instance":"/api/transfers"}

$ # the same key, a different Transfer
$ curl -si -X POST ... -H 'X-Idempotency-Key: 8f14e45f-ceea-467a-9c1c-7c9a5b3f2d10' \
    -d '{"fromAccountId":1,"toAccountId":2,"amountMinorUnits":20000}'
HTTP/1.1 409
{"type":"urn:problem:idempotency-key-reused","title":"Conflict","status":409,
 "detail":"This Idempotency Key already stands for a different Transfer. Use a new key.",
 "instance":"/api/transfers"}
```

Wait and resend the first; never resend the second. A key names one intent, so two payloads
under one key is a client that reused a key it should have replaced, and no amount of
retrying will make the two agree. The key behind the refusal is deliberately not echoed
back, and the record stores a hash rather than the payload, so one caller's request cannot
be read out through another caller's guess at its key.

**A request that was refused releases its key.** Every refusal above — insufficient funds, an
unknown Account, an amount that converts to nothing, a provider that never answered — is a
reservation-time failure, and all of them are retryable under the *same* key once their cause
is gone. So an operator who funds the Account and resends the identical request gets the
Transfer, rather than having to work out whether the first attempt took effect and mint a new
key if it did not. **Failing to the caller is not giving up**: it is what makes "resend it
with the same key" a complete retry policy rather than half of one.

Underneath, requesting a Transfer is three phases: the key is claimed in its own committed
transaction, where a unique constraint serialises concurrent duplicates; the Exchange Rate is
fetched and the amounts converted with **no transaction open and no lock held**; and then the
reservation, the Transfer, its Check Ledger and the key's stored response are written in
**one** transaction. Both halves of that are deliberate. Bundling the last write is what stops
a crash stranding reserved funds behind a key that answers `409` forever; keeping the provider
call out of it is what makes locking two Account rows affordable, because a lock that waited on
somebody else's server would hold up every other Transfer touching either Account.

That is the whole business API so far; the rest of `/v3/api-docs` is still ahead of the
build.

## What is built so far

Tickets 01–17, 19–22, 24–27 and 30–36 of 44: the skeleton, schema management, the package
structure the domain code will be written into, the security chain in front of it, the error
contract every endpoint will answer with, the value type every amount in the system is
expressed in and the single conversion between currencies, the first entity and the first
table, the first endpoint that writes to it and the first that reads it back, the Transfer
and the locking rule the concurrency design rests on, the reservation that rule protects,
the endpoint a client posts a Transfer to and the two it is read back from, the claim on an
Idempotency Key and the duplicate resolution that turns that claim into an answer, the Check
Ledger a Transfer has to clear before it settles, the one operation that answers a Check and
moves the money, the guarded endpoint that operation is reached through, the response that
tells an operator what a Transfer is still waiting on, the third-party Exchange Rate provider
the resilience work is aimed at and the client that survives it, the Transfer that crosses two
Currencies and carries the rate it was quoted at, the table that keeps a committed change and
the news of it from ever disagreeing, the frontend's shell, the generated API types that join
the two halves, the frontend edge that turns Minor Units into decimals, the reading an
operator gets of a failed request, the client half of the Idempotency Key, the stream message
that is nothing but a cache invalidation and the one endpoint that sends it once the change it
describes has committed, and the two ecosystem bets that had to be settled first. **Both bets
won.**

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

Every seeded account starts with nothing reserved, because reserving is what requesting a
Transfer does and the seed writes accounts only — so on a fresh `dev` start, Available
Balance equals balance everywhere, and stays that way until the first `POST /api/transfers`.
That is the derivation working, not the demo data being flat.

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

**A Transfer is written with the Check Ledger it has to clear, in one transaction.** The
Check Ledger is one row per Check the Transfer requires — fraud screening, manual
approval — each recording that Check and how it has been answered, and a Check that has
not answered has no verdict rather than a third kind of verdict. This is the record that
makes *why is this transfer still pending* a question with an answer: the outstanding rows
are the answer, and the same rows are the audit trail and the pending-state screen.
Because they are written in the Transfer's own transaction, a `PENDING` Transfer with an
empty ledger — one nothing would ever settle, reject or explain, holding reserved funds
until a person noticed — cannot exist. The operation that writes them refuses to run
outside a transaction and refuses to open a ledger with no Checks in it, and an ArchUnit
rule holds that a Transfer comes into being in exactly one place, so that is a property of
the design rather than of the one path that exists today.

**That answer is on the wire, and only where it is asked for.** `GET /api/transfers/{id}`
sends a `checks` array — one entry per Check the Transfer requires, each naming the Check
and carrying its `verdict` if one has been given. **An outstanding Check has no `verdict`
member at all**, rather than a null one: a client is made to handle an unanswered Check
instead of remembering that a value it was given may be nothing. The entries come back
ordered by the Check, so the ledger does not reshuffle itself between two refetches of
unchanged data, and the ledger is read in the same transaction as the Transfer, so the
status and the rows explaining it can never be a snapshot apart. The listing endpoint
sends no `checks` at all: what a Transfer is waiting on is what a single Transfer is
fetched to find out, and a list carrying every Transfer's ledger would read a second table
per row to fill a screen that renders none of it. What the response cannot say is *when* a
Check answered — the ledger keeps no timestamp, and why it does not yet is in
[`docs/deferred.md`](docs/deferred.md).

What follows from the ledger is a **pure function of it**: no repository, no clock, no
Transfer. Settle when nothing is outstanding and nothing rejected, reject on the first
rejection, otherwise wait — and **a rejection wins immediately**, because once one Check
has said no, nothing the outstanding ones could say would revive the Transfer and waiting
for them holds an operator's money against a decided outcome. An empty ledger is refused
rather than settled: it satisfies "nothing outstanding, nothing rejected" only because
nobody checked anything. Adding a condition to this gateway is a constant, a line in the
policy and a row in the ledger — no new endpoint, no new state, and nothing in the
settlement rule to revisit. **That extensibility is the whole reason the lifecycle is
asynchronous**, and it is argued in
[ADR-0001](docs/adr/0001-asynchronous-transfer-lifecycle.md).

**A Transfer's lifecycle advances in exactly one place**, and it is a domain operation
rather than an endpoint: recording a Verdict answers one Check, asks the ledger what the
whole of it then supports, and settles or rejects on that answer. The inbound HTTP callback
is an adapter over it and a broker consumer could be a second one, without the domain
changing — which is why the refusals it raises are exceptions about the Transfer rather
than status codes. **Settlement moves both of the source's figures**: the balance falls and
the Reserved Amount falls with it, because the reservation is consumed rather than left
behind for every later overdraft check to test against, and the destination's balance
rises. **Rejection writes no compensating movement**, because no money ever moved — the
reservation is simply given up. That is the payoff of settling asynchronously: *undo a
payment* became *do not make one*.

**What makes two Verdicts arriving at once safe is a row lock on the Transfer, not the
guarded update on its status.** The guarded update is there and it is the same
conditional-update-with-a-rows-affected-check used for the Idempotency Key, but under the
lock it cannot fire, and the failure it does not address is the worse one: two Verdicts
landing together each write their own ledger row, neither transaction can see the other's
until it commits, so both read a ledger with one Check still outstanding and both decide to
wait — leaving the Transfer `PENDING` for ever against a fully approved ledger, holding an
operator's funds. Neither ever reaches an `UPDATE`, so no guard on one helps. Locking the
Transfer first serialises the whole decision, and the second Verdict reads a ledger the
first has committed. The order is **the Transfer, then its Accounts ascending**, which
stays acyclic against the reservation path that takes Account locks only.

A Verdict for a Transfer that has already finished is **refused rather than written into
the ledger of a dead Transfer**, and the refusal carries the status it found: a Check
service that is told `SETTLED` has learnt that its report landed and what it did. Since
these services deliver at least once, that is also the answer a redelivery of the winning
Verdict gets — the money still moved exactly once. Whether a redelivery deserves a
friendlier answer than a refusal is in [`docs/deferred.md`](docs/deferred.md).

**A Check service reaches that operation at
`POST /internal/transfers/{id}/checks/{check}`**, which is the only endpoint in this
application that takes a credential. The Transfer and the Check are the address, so the
body carries the Verdict and nothing else — a request that also named a Transfer in its
payload would leave something to decide which of the two to believe.

`$SECRET` below is `payments.internal.shared-secret`, whose development value is in
[`application.properties`](src/main/resources/application.properties); the
[security section](#security-configured-not-disabled) has the rule it feeds.

```console
$ curl -s -X POST localhost:8080/internal/transfers/1/checks/FRAUD \
    -H 'Content-Type: application/json' -H "X-Internal-Secret: $SECRET" \
    -d '{"verdict":"APPROVED"}'
{"transferId":1,"check":"FRAUD","verdict":"APPROVED","transferStatus":"PENDING"}

$ curl -s -X POST localhost:8080/internal/transfers/1/checks/MANUAL_APPROVAL \
    -H 'Content-Type: application/json' -H "X-Internal-Secret: $SECRET" \
    -d '{"verdict":"APPROVED"}'
{"transferId":1,"check":"MANUAL_APPROVAL","verdict":"APPROVED","transferStatus":"SETTLED"}
```

**The receipt is why this answers `200` and not `204`.** The two calls are the same
request against the same Transfer and they are not the same news: the first reporter learns
its Verdict was not the last word, the second learns that money moved. Under `204` a Check
service would have to fetch the Transfer back to find out, which is a request made only
because the previous response withheld something it already knew. The report is echoed
back too, because a reporter with several Verdicts in flight and a retry policy needs to
know which one it is holding the receipt for.

Every Transfer requires both Checks today, and after the second `curl` account 1's balance
has fallen by €100.50 with its Reserved Amount falling alongside it — the reservation is
consumed, not left behind. A `REJECTED` Verdict on either Check ends the Transfer at the
first one, and gives the €100.50 back.

Three things are refused, each under its own `type` URN:

| Refusal | Status | URN |
|---|---|---|
| No Transfer with that identifier | `404` | `urn:problem:not-found` |
| A Check that Transfer's ledger has no row for | `404` | `urn:problem:check-not-required` |
| A Verdict for a Transfer that already finished | `409` | `urn:problem:transfer-not-pending` |

The middle one is a `404` like the first and still not the same fact — the wrong identifier
against a reporting service running on stale configuration — and telling them apart by
which properties happen to be present rather than by the URN is the branching the error
contract exists to prevent. The last carries the status the Transfer had already reached,
which is what turns a refusal into news, and carries no `Retry-After`: a Transfer never
leaves a terminal status, so a caller that retried it would retry for ever.

**Virtual threads are on** (`spring.threads.virtual.enabled=true`), and they are
load-bearing rather than a nicety. The stand-in Exchange Rate provider is a real HTTP
endpoint inside this same application, so serving a transfer means one request thread
waits on a second thread of the same server. On a bounded pool that can deadlock against
itself; virtual threads are not scarce, which is also why the design declines a circuit
breaker. `ApplicationBootsTest` asks the running container what kind of thread served the
request rather than asserting the property is set — flip the flag off and it fails.

**A committed change and the news of it cannot disagree, because the news is a row written
by the same transaction.** An Outbox Event describing what happened to a Transfer is
recorded into `outbox_events` by the operation that caused it, and the recorder refuses to
run outside a transaction rather than opening one of its own — a recorder that quietly
opened its own would pass every test that writes an event and reads it back, while breaking
the one property the table exists for, in the direction nobody looks. So the event and the
change commit together or neither of them happened. A scheduled poller then publishes what
has not gone out yet and marks it sent.

**It publishes first and marks second, each mark in its own transaction, and that ordering
is the whole of the delivery guarantee.** Marking first would be at-most-once: a publish
that then failed would leave a row claiming the news had gone out, and a Transfer that
really did settle would be unannounced with nothing anywhere recording that fact — which is
precisely the failure the outbox is built to prevent. Publishing inside the mark's
transaction would hold one open across network I/O, the shape the locking rules above exist
to avoid, and would still not make the pair atomic. So **delivery is at-least-once**: a
publish that succeeded and a mark that did not commit costs a duplicate, and consumers
deduplicate — which they must anyway, since exactly-once across a network is not something
a retry can buy. A publish that throws costs its own row and no other; the run logs it,
leaves it unsent and carries on, and the next run is the entire retry policy.

**Kafka would be a transport swapped in under `publish()`, not a replacement for the
table.** "Commit to the database, then send to Kafka" is still two writes with no
transaction spanning them, which is the reason the table is here at all. In this build that
one method writes a structured log line — a real implementation with a stand-in far end, so
the outbox, the poller and the at-least-once contract above them all run exactly as they
would against a broker. The scheduler driving the poller is gated by
`payments.scheduling.enabled`, which turns the background actor off without taking the bean
away, so a test can drive a poll by hand and assert what a failure leaves behind instead of
racing a poller to the row. Retry with backoff, a dead-letter path, ordering between two
events on one Transfer, and archival of a table that only grows are deferred with reasoning
in [`docs/deferred.md`](docs/deferred.md).

**One event stream serves every browser, and a message on it carries nothing but a hint.**
`GET /api/events/stream` is a single server-sent-events endpoint rather than one per
Transfer, and everything it sends is `{"type":"TRANSFER_SETTLED","transferId":7}` — an
event type and a Transfer ID, and no more. That emptiness is the design rather than a
shortcut taken: a message carrying only a cache key is a message the browser cannot merge
into anything, reconcile against anything, or need in order, so the frontend's entire
handling of the stream is one pure function from a frame to a list of stale query keys.
*The stream carries hints; the REST endpoint carries truth.* It is the deliberate opposite
of what the outbox sends, for the reason above: a service that had to call back for the
amount is the coupling the outbox exists to avoid, while a browser calling back for the
amount is a request to an API it is already holding a connection open to.

**There is no catch-up — no replay, no buffer, no `Last-Event-ID` — and what pays for that
is that a hint is only ever sent after the change it describes has committed.** A browser
answers a hint by refetching, so one that overtook its own commit would send the browser to
read the row as it was and never tell it again: a page permanently showing the old status,
with the backend correct, the frontend correct, and nothing in any log to say what
happened. So the send is not a call. The operation that settles a Transfer publishes an
application event from inside its transaction and the stream listens for one *after* that
transaction commits, which makes the ordering structural instead of a comment — a
settlement that rolls back sends nothing. The same indirection is what keeps a socket
write off the path holding row locks on a Transfer and two Accounts, which is the hazard
`LockedPathTouchesOnlyTheDatabaseTest` exists to forbid: writing to an unknown number of
possibly-slow browsers is the clearest example there is of a lock waiting on something
slower than the database.

Given no catch-up, a dropped connection costs one refetch and nothing else, which is why
the connection is bounded at fifteen minutes rather than held open indefinitely — the
bound is there so a browser that vanished without closing its socket is not registered for
ever. A subscription writes one SSE comment immediately, and that is load-bearing rather
than a greeting: Spring hands the container an emitter and writes nothing itself, so
without it the response never leaves Tomcat's buffer and a browser's `onopen` — the event
its convergence refetch hangs off — would fire whenever some unrelated Transfer next
finished instead of when it subscribed. Scoped subscriptions and running this stream
across more than one instance are deferred with reasoning in
[`docs/deferred.md`](docs/deferred.md).

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

**There is one real authorization rule, and it is a shared secret on `/internal/**`** — the
prefix the Verdict callback arrives on. This is where "no authentication" stops being
theoretical: an open Verdict endpoint means anyone approves their own Transfer and walks
past fraud screening. It is not a contradiction of the paragraph above, which declined to
model *user* identity; this is service-to-service trust across a boundary the asynchronous
lifecycle created.

```properties
# What a Check service presents in X-Internal-Secret. A development value,
# and the one thing in application.properties a deployment must replace.
payments.internal.shared-secret=development-secret-not-for-deployment
```

A blank value is a startup failure rather than a permissive rule — an empty configured
secret would let every request carrying an empty header through while the configuration
still claimed the endpoint was guarded.

Four things about that rule are decisions rather than defaults:

- **It guards the prefix, not the handler.** A second internal endpoint inherits it instead
  of having to remember it; the one that forgot would be the open one, and it would look
  exactly like the ones that did not. The test posts to a path under `/internal` that has
  no controller behind it at all.
- **It is an `AuthorizationManager` on the chain, not a filter of our own.** The whole
  policy then reads in one method, and a reader of `SecurityConfiguration` cannot miss a
  rule that lives somewhere else.
- **A refusal is `403`, never `401`, for a missing and a wrong secret alike.** A `401` is
  obliged to carry a `WWW-Authenticate` challenge naming a registered HTTP authentication
  scheme, and a bare secret in a bespoke header is not one — so the challenge would either
  be absent, making the response malformed, or name a scheme this API does not accept. The
  two refusals are answered identically down to the wording, so that a caller probing the
  endpoint cannot learn which header is the one being checked.
- **It grants nothing and authenticates nobody.** Presenting the secret means a request may
  proceed, not that this application knows which Check service made it. One shared value
  cannot be rotated per caller or revoked for one of them, and an audit trail cannot name
  the reporter; a real service identity, and a separate port the public ingress never
  routes to, are in [`docs/deferred.md`](docs/deferred.md).

The endpoint is in the published OpenAPI document on purpose — a Check service being
integrated is the reader that document exists for — with an `apiKey` scheme attached to the
operations under `/internal` and to no others, which is also what puts the Authorize box in
Swagger UI. Attaching it document-wide would describe the whole API as needing a credential
the public half does not, sending an integrator to look for one nobody will issue them.

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
   forgetting to permit it turns every 404 and 405 under a permitted path into a 403 — a
   plausible-looking status that hides the real one. Now that a denial carries a problem
   document it would hide it *convincingly*, under a URN naming a credential nobody was
   ever asked for.

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

**One document is written somewhere else, and it has to be.** A refusal from the security
filter chain is raised before the dispatcher, so `ProblemDocumentAdvice` never sees it —
the advice deliberately rethrows an `AccessDeniedException` rather than answering one. A
denial used to be an empty body, which was a complete answer while the only thing denied
was a path that does not exist. The shared secret changed who meets it: an operator wiring
a Check service against the wrong value has to act on the response and has nothing else to
go on. So the chain carries a handler of its own that writes the same
`urn:problem:forbidden` document through the same `ProblemType` vocabulary, and
`SecurityChainTest` asserts the document rather than the status.

One refusal is still a bare `403`, and that is the point of it: a CORS preflight from an
unlisted origin is answered by `CorsFilter`, ahead of the authorization filter, and a
browser has to be able to tell "this origin may not ask" from "this caller may not have
it".

**The client half of the rule is `frontend/src/api/problem.ts`**, which maps a URN to a
title, a body and whether retrying will help — and reads no other member of the document,
asserted against its own source rather than left as an intention. It is where the two
`409`s stop being the same news: one advises waiting and trying again, the other says a
retry can never succeed. The wording is the frontend's own, because a document's `title`
is a status reason phrase and its `detail` is written for whoever is reading the response,
neither of which an operator can act on. [The frontend README](frontend/README.md#what-an-operator-is-told-when-a-request-fails)
has the surface.

## The Exchange Rate provider is a stand-in that misbehaves

**The mock FX provider is a real HTTP endpoint inside this application**, switched on by
the `mock-fx` profile and absent without it. It is an endpoint rather than a stubbed bean
because a bean sits *above* the HTTP client: the timeouts, retries and error mapping that
the resilience work exists to demonstrate would never run against one, and the thing being
demonstrated would be the thing mocked out.

```console
$ curl -s 'localhost:8080/mock/fx/rates?base=EUR&quote=HUF'
{"base":"EUR","quote":"HUF","rate":395.000000}

$ curl -s 'localhost:8080/mock/fx/rates?base=EUR&quote=HUF'   # the same call, moments later
{"error":"rate_service_unavailable",
 "message":"The rate service is temporarily unavailable. Try again shortly."}
```

It quotes EUR, USD and HUF from a single pivot rather than from a table of pairs, so its
cross rates cannot disagree with each other. **Two dials make it misbehave**, and they are
the reason it is worth running at all:

| Property | Default | What turning it does |
|---|---|---|
| `payments.mock-fx.failure-rate` | `0.3` | The share of requests answered `503` instead of a rate. `0` is a dull, reliable provider — which is what the tests that are about something else set. `1` fails every call, which is how you watch a retry budget run out. |
| `payments.mock-fx.latency` | `200ms` | How long **every** response is held back, the failures included. Raise it past a client's read timeout to make the timeout fire; the failures are slow too, so a timeout cannot be tuned against the success path alone. |

**It is deliberately outside this application's cross-cutting layers**, because a third
party that wore our security headers, our error shape and our CORS policy would be our own
application in a costume — and the client written against it would be tested against a
fiction. Four mechanisms, each verified against a running server rather than by reading
the configuration back:

- **Security is bypassed, not permitted.** `web.ignoring()` on `/mock/**` takes the paths
  out of the filter chain entirely; under `permitAll` the response would still come back
  carrying `X-Content-Type-Options` and the rest, which is our server signing somebody
  else's response. The absent header is what the test asserts on. Spring Security logs a
  startup warning advising `permitAll` instead — correct for endpoints that are ours, and
  wrong for this one.
- **Its errors are its own**, a plain `{error, message}` and never an RFC 9457 problem
  document, so the claim above — that a caller who has parsed one of ours has parsed all
  of them — stays true. They come from `@ExceptionHandler` methods on the mock controller,
  which win over any `@ControllerAdvice` without anything having to be ordered.
- **It is out of the CORS mapping**, which covers `/api/**` only. It stands in for a
  service reached server-to-server; no browser should be able to call it. This one is
  asserted twice on purpose — a preflight that comes back allowing nothing is the
  caller's-eye view, but it would stay green even if the mapping did name `/mock/**`,
  because the security bypass means the CORS filter never runs on those paths. The
  mapping is therefore also asked directly what policy it holds for them, which is the
  half that can fail on its own.
- **It is hidden from `/v3/api-docs`**, which is a layer of ours in the same sense as the
  others and the only one whose leak outlives the process: the frontend's types are
  generated from that document against a *running* backend, so without this a developer
  who ran with `mock-fx` and regenerated would commit a third party's endpoint into
  `schema.gen.ts`.

**The consequence worth keeping in view: the application now calls itself over HTTP.** On
a bounded thread pool that can deadlock under load — an inbound request holds a thread
while waiting for a second thread to serve its own outbound call — which is what makes
`spring.threads.virtual.enabled=true` load-bearing here rather than a nicety.

## The client that talks to it expects it to misbehave

**`ExchangeRateProvider` is a port with one method** — a rate for a Currency pair — and
everything that makes an unreliable third party survivable sits behind it: the timeouts,
the bounded retry, and the translation of the provider's bad days into failures this
application has words for. The HTTP implementation is package-private, so no caller can
name it and no caller has to. The stand-in above is reached over real HTTP, the same way a
paid service would be, so **a provider that speaks the shape below is a property change**
— one line of `application.properties` and nothing recompiled. That shape is
`GET {base-url}/fx/rates?base=…&quote=…` answering `{"base","quote","rate"}`, and it is
hard-coded: a provider that spells its path, its parameters or its JSON differently is a
second implementation of the port rather than a property. Which is what the port is for —
the timeouts, the retry and the error mapping below are stated once and inherited by both.

| Property | Default | What it does |
|---|---|---|
| `payments.fx.base-url` | `http://localhost:8080/mock` | The provider's root, everything below which is its API and not ours. This is the substitution seam — a provider speaking the shape above is this one line. |
| `payments.fx.connect-timeout` | `1s` | How long to wait for the provider to accept a connection. |
| `payments.fx.read-timeout` | `2s` | How long to wait for its answer once it has. Set well above the stand-in's default latency, so raising that dial reaches the timeout path rather than a hung test. |
| `payments.fx.max-retries` | `2` | Retries **after** the first attempt, so a provider that fails everything is called three times. |
| `payments.fx.retry-delay` | `100ms` | The wait before the first retry, doubled for each one after it. |

**None of those five has a fallback in code, deliberately.** The last two are read twice —
once bound onto a settings record, once as a `${…}` placeholder inside the `@Retryable`
annotation, which resolves against the environment and cannot see a default written on the
record. Rather than keep two copies of one number, every default lives in
`application.properties`, and deleting a line fails startup instead of quietly running a
policy that differs from the one documented here.

The policy itself, in one sentence: **a `5xx` or a connection that fails or goes quiet is
retried up to the budget above, and everything else — a `4xx` above all — fails on the
first attempt**, because a deterministic refusal asked three times is three refusals and
one lie about how hard we tried. Both timeouts are set because without them "the provider
is slow" has no upper bound, and a request parked forever is one holding an in-progress
Idempotency Key. There is no cache and no circuit breaker: a breaker protects a scarce
thread pool and virtual threads are not scarce, and a cache is a second source of truth for
a number that is about to be written onto a Transfer. Both are recorded, with what it would
take to add them, in [`docs/deferred.md`](docs/deferred.md).

**When the budget runs out, the caller gets one failure whose whole meaning is "try again
later"** — `ExchangeRateUnavailableException`, naming the pair and how many attempts the
policy allows — and that is a different type from the one a refusal produces. The distinction is the point: a
`503` with a `Retry-After` in answer to a request that will never succeed is worse advice
than no advice, so only exhaustion becomes it. That the retries genuinely happened is
asserted from Spring's own `MethodRetryEvent` rather than by counting requests, since a
plain loop in the client would produce the same request count and the same green test.

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
│   └── checks/    the Check Ledger, and the policy that decides what goes in it
├── idempotency/   run-once-per-key replay protection      · one public port
├── fx/            exchange rates from an unreliable provider · one public port
├── outbox/        events written in the same transaction as the change · one public port
├── stream/        the browser's event stream · two value types, and no port
├── mockfx/        the stand-in Exchange Rate provider — depends on nothing
└── common/        Money, Currency, problem types — depended on by everything
```

Dependencies run one way: `transfers` onto `accounts`, `fx`, `idempotency`, `outbox` and
`stream`, and nothing points back. Three public ports in total — `stream` exports the two
value types a hint is made of and no port, because there is one implementation and no seam.

`transfers` depends on `stream` without naming anything that sends: it publishes a Spring
application event, and the container is the only thing joining the two halves. That is what
the locked-path rule leaves as the option, and it buys the after-commit ordering in the same
move.

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
