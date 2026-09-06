# Deferred decisions

Running list of things consciously left out, with the reasoning. Feeds the
README's TODO section, which the task grades as heavily as the code.

Each entry: what was deferred, why, and what it would take to do properly. `§`
references point into the [design decisions](design-decisions/README.md).

---

## Client retry semantics for `409 Conflict`

**Deferred.** Two different situations return `409`, and a client must treat
them oppositely:

- **Request in progress** — the same key and payload are already being
  processed. Retrying later is correct and expected.
- **Idempotency key reused with a different payload** — the client has made a
  mistake. Retrying will never succeed and must not be attempted.

MVP returns `409` for both, with a distinct problem `type` URN so the two are
at least distinguishable programmatically, and `Retry-After` present on the
in-progress case only.

**Why deferred:** the remaining work is largely a UX and client-contract
problem — how the frontend surfaces each case, whether it retries
automatically, what the user is told — rather than a backend correctness gap.
The backend already reports enough to tell them apart.

**What it would take:** decide whether the payload-mismatch case deserves its
own status code (`422`) rather than sharing `409`; define client retry policy
(backoff for in-progress, hard stop for mismatch).

**Partly closed by [ticket 34](design-decisions/34-problem-document-module.md).**
The two cases are now surfaced distinctly: `problemToMessage` tells the operator
that the in-progress `409` is worth trying again and that the reused key never
will be. What is still deferred is the *automatic* half — nothing reads
`Retry-After` and schedules a retry against it; `retry.ts` declines to retry any
`409` precisely so that it does not race a header it ignores. A client that
backs off on the header, and only for the in-progress URN, is the remaining
work.

---

## Authentication

**Deferred.** No authentication scheme. Spring Security is wired with an
explicit, justified stateless filter chain that denies by default and permits
the public API by name, so nothing a caller reaches needs credentials.

**Why deferred:** nothing in the functional or non-functional requirements
references a caller — transfers operate on bare account IDs, and all three
screens are unscoped. Building auth spends budget on something the task never
asks for.

**What it would take, in order:** bearer token validation → ownership check on
the transfer source account → scoping the transactions list to the caller.

---

## Errors raised before the dispatcher

**Deferred.** Every error from application code is an RFC 9457 problem document
with a `type` URN. A refusal from the Spring Security filter chain is not: it is
raised before the dispatcher, so `ProblemDocumentAdvice` never sees it, and it
is answered with the status alone and an empty body.

**Why deferred:** nothing is denied on purpose yet. The chain denies paths it
does not name, which is a caller asking for something that does not exist, and
`403` with no body is a complete answer to that. The first refusal a client is
meant to *read* arrives with the shared secret on the internal Verdict endpoint,
and the decision belongs with it.

**What it would take:** an `AuthenticationEntryPoint` and an
`AccessDeniedHandler` on the chain that write the same document through the same
`ProblemType` vocabulary. The advice already rethrows `AccessDeniedException`
rather than answering it, so that translation stays where it can see the
security context.

---

## Account ownership (`User` / `Customer`)

**Deferred.** `Account` is the atomic entity. No owner is modelled.

**Why deferred:** no requirement queries, filters, or authorises by owner. An
owner entity with no behaviour is schema and test setup for nothing.

**What it would take:** an `owner` field on `Account` and a column beside it —
additive and non-breaking, since nothing reads the entity positionally and the
response is assembled separately.

---

## Idempotency keys are globally scoped

**Deferred.** Keys live in one namespace shared by all callers.

**Why deferred:** direct consequence of having no caller identity — there is
nothing to scope a key to.

**Residual risk:** key-squatting. A caller who guesses a key before its
legitimate owner uses it claims it first, and the legitimate request gets
`409` and never executes. Denial of service on one transfer; no money moves
and nothing is disclosed. Mitigated in the MVP by requiring keys to be
well-formed UUIDs, making a guess a 122-bit problem.

**What it would take:** a composite unique key of `(caller, idempotency_key)`
— which requires the caller identity deferred above.

---

## A claim stranded by a crash is never cleared

**Deferred.** A record reaches `IN_PROGRESS` in its own committed transaction
and leaves it only by the process that claimed it. If that process dies in
between — a crash, a kill, a container rescheduled — the row stays
`IN_PROGRESS`, and §5 answers `IN_PROGRESS` with `409 Conflict`. For that key,
permanently.

**Why the risk is small.** The claim commits before anything else happens, so a
stranded row means no funds were reserved and no Transfer exists. Nothing is
lost and nothing has to be reconciled; the client resubmits under a new
Idempotency Key and the transfer goes through. What is spent is the key, and
keys are the client's to generate. §4 originally described this window as "what
the retry path exists for", which is not true — the retry path is the `FAILED`
one — and [ticket 16](design-decisions/16-idempotent-execution.md) struck the
clause.

**Why deferred:** clearing it needs to distinguish "still running" from
"abandoned", and the only honest way to do that is elapsed time. That means a
`claimed_at` column and something that sweeps on it — and a sweep interval is a
guess about the longest legitimate request, which for a design whose second
phase calls a deliberately unreliable provider is a guess worth making with
measurements rather than without. The record ships without the column on the
same principle as `Transfer`'s single timestamp: the ticket that first reads a
claim's age adds it with a reader to shape it.

**What it would take:** `claimed_at` on `idempotency_records`, a reclaim guarded
on `status = 'IN_PROGRESS' AND claimed_at < ?` alongside the `FAILED` one — the
same conditional-update-plus-rows-affected shape, so exactly one sweeper wins —
and a timeout derived from the FX provider's own. The expiry reaper of §14 is
the scheduled job it would live in. Note that a sweep that is too eager is worse
than none: it hands the key to a second caller while the first is still working,
which is the double-charge the whole mechanism exists to prevent.

---

## Scoped queries and event streams

**Deferred.** `GET /api/transfers` and `GET /api/accounts` return everything to
anyone, and `GET /api/events/stream` pushes every transfer's events to every
subscriber. The frontend filters by transfer ID client-side, which is a
rendering convenience and not a boundary.

**Why deferred:** a consequence of two earlier deferrals, not a design choice —
nothing in the model asserts transfers are public. Scoping needs identity
(above) *and* ownership on `Account` (above); with neither, there is nothing to
filter by.

**What it would take:** the auth chain, then an owner predicate on the queries
and on stream subscription.

---

## Double-entry ledger and internal accounts

**Deferred.** Balances are two fields on `Account`; there is no posting log and
no suspense, clearing or FX-position accounts.

**Why deferred:** the two-field model is what banks present to customers and is
sufficient for every requirement here. The posting log underneath is a
substantially larger build that would also restructure the locking design
(see [design decisions](design-decisions/00-initial-decisions.md) §6, §13).

**What it would take:** an immutable entry table written in the same
transaction as every movement, a reconciliation job proving entries sum to
balances, and internal accounts so cross-currency movements balance per
currency.

---

## Running more than one instance

**Deferred.** The design assumes a single application instance.

**Why deferred:** nothing in the task requires horizontal scaling, and both
gaps are one paragraph to describe.

**Two known breaks:**

- **The outbox poller would double-publish** — two instances read the same
  unsent rows. Fix: `SELECT ... FOR UPDATE SKIP LOCKED` on the poll query.
- **Event streams are instance-local** — a connection is held by one instance,
  so a transfer settling on another never reaches it. Fix: shared pub/sub
  between instances.

---

## Outbox robustness

**Deferred.** The poller handles the happy path only.

**Missing:** retry with backoff on publish failure, a dead-letter path for
poison events, ordering guarantees for two events on the same transfer, and
archival of the ever-growing table.

**What it would take:** Spring Modulith's Event Publication Registry provides
most of it — durable publication tracking, republish on restart, and a
staleness monitor for publications stuck mid-flight — and 2.0 is aligned with
Spring Boot 4. Kafka would be the transport underneath, not a replacement for
the outbox.

---

## Separate port for internal endpoints

**Deferred.** `/internal/**` is protected by a shared secret on the same
connector as the public API.

**Why deferred:** the secret is sufficient for this build; a second connector
adds moving parts locally and in tests.

**What it would take:** bind internal endpoints to their own port that the
public ingress never routes to. Defence in depth wants both, not either.

---

## Terminal vs retryable transfer failures

**Deferred.** Any reservation-time failure is `FAILED` and retryable.

**Why deferred:** the spec's wording points at it, it cannot double-charge, and
retrying insufficient funds after a deposit lands is arguably the behaviour a
client wants.

**What it would take:** an exception taxonomy separating deterministic
rejections (insufficient funds, unknown account) from unavailability (FX
provider down), with rejections stored and replayed like successes rather than
re-executed. This is what Stripe does — the key records an *outcome*, not just
a success.

---

## Re-quoting the exchange rate at settlement

**Deferred.** The rate is locked when the transfer is requested.

**Why deferred:** this is a business decision about who carries FX movement
risk between authorization and settlement, not a technical one. Staleness is
bounded already, because the quote's validity window and the check deadline are
the same clock.

**What it would take:** a re-quote at settlement with a tolerance band, and a
policy for what happens when the new rate falls outside it.

---

## The rounding remainder has nowhere to go

**Deferred.** Money is not conserved across a cross-currency transfer: the
source is debited `srcMinor`, the destination is credited the rounded converted
amount, and the sub-minor-unit remainder simply vanishes
(design decisions §16).

**Why deferred:** it is a direct consequence of the double-entry deferral above,
not an independent decision. With no posting log there is no second side for the
remainder to land on, and with a single rounding site the error is bounded at
half a minor unit per transfer.

**What it would take:** an FX position account per currency, credited or debited
with the remainder in the same transaction as the movement, so each currency's
books balance independently. That is the same build as the double-entry ledger,
and the reason the two are deferred together.

---

## Verifying the locking design against Postgres

**Deferred.** The concurrency tests run on H2 (design decisions §25), which
is what the application also runs on.

**Why deferred:** H2's MVStore does take real `SELECT … FOR UPDATE` row locks
and does detect deadlocks, so the §6 tests are genuinely meaningful rather than
theatre. Testcontainers would add a Docker prerequisite to `./mvnw test` for a
delta this build does not need.

**Residual risk, and it is real:** H2's default lock timeout is around one
second, where Postgres waits indefinitely. A test that passes here can fail
there on timing alone, and the ascending-ID lock ordering is exactly the kind of
claim that deserves the stricter engine.

**What it would take:** the same suite against a Postgres Testcontainer in CI.
**Treat this as a production prerequisite, not a nice-to-have** — it is the
check on the design's central integrity claim.

---

## True end-to-end testing

**Deferred.** Playwright runs *end-to-mock* (design decisions §24): a real
browser against scripted `page.route` responses and a stubbed `EventSource`.

**Why deferred:** it buys determinism, no orchestration, and no flake, and it
covers the frontend half completely.

**The gap, named:** end-to-mock proves the frontend handles every response shape
correctly, and proves nothing at all about whether the backend produces those
shapes. Generated OpenAPI types (§26) close most of that gap statically, but
they cannot catch behaviour — only structure.

**What it would take:** a Playwright project that boots the real application
with the `mock-fx` and stub-consumer profiles, seeds accounts through the API,
and drives the true `PENDING → SETTLED` transition through the real SSE stream.
The awkward part is not the wiring but the waiting: the stub consumer's delay
becomes test timing.

---

## Testing the FX read timeout

**Deferred.** `MockRestServiceServer` scripts the FX provider's *responses*
(`503, 503, 200`) but sits above the transport, so it cannot simulate a
connection that accepts the request and then goes quiet.

**Why deferred:** it is the one part of §27's resilience that the chosen test
seam cannot reach, and reaching it means adding a dependency for a single test.
WireMock was dropped along with the standalone mock provider (§28), not
independently.

**What it would take:** WireMock's `withFixedDelay`, or a purpose-built slow
endpoint on the `mock-fx` profile, plus an assertion that the client gives up at
the configured timeout rather than at the provider's convenience.

**Unlike most entries here, this one may reopen during the build rather than
after it.** If the timeout path turns out to need real coverage while writing
§25's suite, re-adding WireMock for that single test is the answer — it is a
test-scoped dependency, so it costs nothing the reviewer has to run.

---

## Circuit breaker on the FX provider

**Deferred.** Timeouts and bounded retries only (design decisions §27).

**Why deferred:** a circuit breaker exists to stop failing calls from consuming
a scarce thread pool. With virtual threads enabled, threads are not scarce, and
the retry budget is already bounded by the timeout — so the breaker would be
protecting against a pressure this deployment does not have.

**What it would take:** Resilience4j's `@CircuitBreaker` alongside the existing
`@Retryable`, with a fallback that fails fast while open. It becomes worthwhile
at the point where FX outages are long rather than intermittent, or where the
provider bills per call.

---

## Single-jar packaging

**Deferred.** Two processes in development — `./mvnw spring-boot:run` and
`npm run dev`, with Vite proxying `/api` (design decisions §20).

**Why deferred:** improving the developer experience is a stretch goal, and the
two-command setup is honest about what is running.

**What it would take:** `frontend-maven-plugin` building the SPA into
`target/classes/static/` during `package`.

**The trap that comes with it, recorded so it is not rediscovered:** once the
frontend is served by Spring, deep-linking or refreshing `/transfers`
**404s** — Spring's static resource handler looks for a file literally named
`transfers`. The fix is about fifteen lines of `WebMvcConfigurer` forwarding
unmatched non-asset paths to `index.html`, explicitly excluding `/api/**` and
`/internal/**` so a genuinely missing API route still returns a `404` rather
than an HTML page.

---

## Stale generated API types

**Deferred.** `frontend/src/api/schema.gen.ts` is generated by hand and committed
(design decisions §26, [ticket 32](design-decisions/32-openapi-type-generation.md)).

**Why deferred:** a CI pipeline does not exist for this build, so the
enforcement point does not exist either. Nor can the build enforce it the way
`npm run build` regenerates the route tree: generating this file needs a running
backend, and a frontend build has neither one nor any business requiring one.

**Residual risk:** the types are the mechanism that turns backend drift into a
frontend compile error. If they are regenerated only when someone remembers,
they eventually describe an API that no longer exists — and they will still
compile, silently.

**What is in place instead:** `npm run api-types` regenerates the file against a
running backend, named in both READMEs beside *when* to run it — any change to an
endpoint, a request or response shape, or the problem-type vocabulary. Unlike the
route tree, it is **not** marked `linguist-generated` in `.gitattributes`: a change
in it is the API moving underneath four screens and is meant to be read, not folded
away in a pull request.

**What it would take:** a CI step that starts the app, regenerates, and fails on
a non-empty diff.

---

## Idempotency Keys need a secure context to be minted

**Deferred.** `frontend/src/api/idempotency.ts` mints keys with `crypto.randomUUID`,
which the Web Crypto API exposes only in a secure context. There is no fallback.

**Why deferred:** every way this app is meant to run already is one — the Vite dev
server on `localhost`, and any real deployment, which would be over HTTPS. Writing a
fallback would mean writing a second, weaker UUID source for a situation that is a
misconfiguration rather than a supported mode.

**Residual risk:** served over plain HTTP from anything but `localhost` — a LAN address
during a demo is the realistic case — `crypto.randomUUID` is `undefined` and the
Transfer form throws a `TypeError` on submit. The failure is loud and immediate rather
than silent, which is the right way round, but it is confusing if nobody has met it.

**What is in place instead:** the constraint is named in the
[frontend README](../frontend/README.md#the-idempotency-key-and-what-it-identifies)
beside the module it applies to.

**What it would take:** nothing, if the app is served over HTTPS. If plain-HTTP hosting
ever has to be supported, a `Math.random`-based version 4 UUID behind a feature check —
uniqueness is what an Idempotency Key needs, not unguessability — with a comment saying
why the weaker source is acceptable *here* and nowhere else.

---

## Fetching a single Account, and the `Location` header that waits on it

**Deferred.** There is no `GET /api/accounts/{id}`, and consequently the `201`
from `POST /api/accounts` carries no `Location` header. The created Account's
identifier is in the response body.

**Why deferred:** no screen asks for one. Ticket 10's list endpoint is what the
frontend reads, and nothing in the plan fetches an Account on its own. Adding
the header without the resource is the part that would be wrong rather than
merely incomplete — a `Location` pointing at a `404` breaks the client that
trusts it and helps no one, whereas its absence sends every client to the body,
which works.

**What it would take:** the endpoint and the header together, in that order. The
endpoint is a repository method and a controller mapping over the
`AccountResponse` that already exists; the header is then one line in
`AccountController`. Worth pairing with ticket 12's locking read so that the two
ways of loading one Account are decided at the same time rather than the second
being modelled on the first by accident.

**Precedent set.** [Ticket 15](design-decisions/15-list-transfers-endpoints.md)
did exactly this for Transfers: `GET /api/transfers/{id}` and the `Location` on
`POST /api/transfers` shipped together, in that order, rather than the header
going out ahead of the resource it names. The rule above is therefore a followed
rule rather than an untested preference — and the Account half stays deferred
only because no screen asks for it, which is still true.

---

## API versioning

**Deferred.** No `/v1` prefix; paths are `/api/transfers`, `/api/accounts`.

**Why deferred:** there is exactly one client and it ships in the same
repository. A version prefix with a single version communicates a compatibility
promise nobody has asked for.

**What it would take:** the prefix is trivial to add; the decision that is not
trivial is which strategy to commit to — URL path, `Accept` header, or a
version header — and that is worth making when the second client exists rather
than before.

---

## Pagination

**Deferred.** `GET /api/transfers` and `GET /api/accounts` return every row.

**Why deferred:** a demo dataset is tens of rows, and pagination is a contract
decision (offset vs cursor) that is cheaper to make correctly once the access
patterns are real.

**What it would take:** cursor pagination keyed on `(created_at, id)` for
transfers — offset pagination skips and duplicates rows when new transfers are
inserted while a user pages, which for a list that grows at the head is the
common case, not the edge case.

**The key already exists.** [Ticket 15](design-decisions/15-list-transfers-endpoints.md)
built the Transfers listing, and it orders by `created_at DESC, id DESC` — the
cursor key above, for the independent reason that without the tie break the list
reshuffles itself between two refetches. So pagination is an addition to that
query rather than a change of its order, and no client's idea of "newest first"
moves when it arrives.

**There is no index on `created_at`.** Deliberately left with this entry rather
than added by ticket 15: the demo dataset is tens of rows, and the index wants
designing against the real paging query rather than guessed at ahead of it.

---

## Which Checks a Transfer requires is compiled in

**Deferred.** `CheckPolicy` names the required Checks in code, so every Transfer
requires the same two and changing that is a deploy.

**Why deferred:** the policy is a seam, not a placeholder — it takes the Transfer
and returns a set, so the first conditional rule ("manual approval above ten
thousand") is a line inside it and nothing downstream changes. What is deferred is
only the *source* of the answer, and configuring it before there is a second
answer to configure would be a table, an admin surface and a cache standing in for
one `EnumSet`.

**What it would take:** a rules table keyed on the dimensions that actually drive
the decision (amount band, currency pair, destination), read under the same
transaction as the Transfer so a policy change cannot land between the two writes.
The ledger and the decision function need no change: they count rows and do not
know how many there ought to be.
