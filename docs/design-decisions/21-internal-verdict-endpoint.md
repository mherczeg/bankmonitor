# Ticket 21 — the Verdict callback, behind a shared secret

§9's endpoint and §10's rule, built together because neither is worth much
without the other: `POST /internal/transfers/{transferId}/checks/{check}` is a
thin adapter over [ticket 20](20-record-verdict.md)'s `recordVerdict`, and the
security chain refuses it to anyone who cannot present the configured secret.

Touches §9, §10 and §18 of the [initial decisions](00-initial-decisions.md), and
closes [ticket 04](04-security-chain-cors.md)'s open question about whether a
denial gets a body. It contradicts nothing.

---

## The refusal a caller is meant to read

Ticket 04 left a denial with an empty body and said why that was enough: the
chain denied paths it does not name, which is a caller asking for something that
does not exist, and `403` with no body answers that completely.

The secret changes the population. The caller that meets this refusal is an
operator wiring a Check service against the wrong value, or a deployment where
the secret was never set — someone who has to act on the response and has
nothing else to go on. A client that has learnt to parse this API's failures
should not meet an empty body exactly where it most needs to know what happened,
so `DeniedAsAProblemDocument` writes the §18 document for both halves of the
chain's refusal contract.

Both halves, and the same document from each. Spring Security chooses between
`AuthenticationEntryPoint` and `AccessDeniedHandler` on whether the caller is
authenticated; this application authenticates nobody, so only the entry point
runs today. Writing the pair as one class is what stops the day something does
authenticate from producing an empty `403` nobody noticed.

The document is written in the filter chain rather than by
`ProblemDocumentAdvice` because a denial never reaches the dispatcher. That is
the whole of why this was deferred in the first place, and it is also why the
`instance` member is set by hand here and nowhere else: the converter that fills
it in from the request URI is Spring MVC's return-value handling, which a
refusal raised in front of the dispatcher never gets to.

**Measured, not assumed.** `SecurityChainTest.deniesAPathTheChainDoesNotName`
now asserts the document rather than the status, so the chain answers one denial
the way it answers the other. Its neighbour asserts the opposite about a CORS
preflight from an unlisted origin: `CorsFilter` writes that refusal itself,
ahead of the authorization filter, and it shares only the status code. A browser
that could not tell "this origin may not ask" from "this caller may not have it"
would be reading one refusal as the other.

### `403`, not `401`, for missing and wrong alike

RFC 9110 obliges a `401` to carry a `WWW-Authenticate` challenge naming an HTTP
authentication scheme. A shared secret in a bespoke header is not one, so the
challenge would either be absent — making the response malformed — or name a
scheme this API does not accept, sending a client to retry in a way that cannot
work. `403` claims no scheme, which is the honest description of the rule.

The header is `X-Internal-Secret` for the same reason: `Authorization` carries a
registered scheme, and none of them describes this.

A missing secret and a wrong one are answered identically, down to the wording,
and `InternalVerdictContractTest` compares the two response bodies byte for byte
rather than trusting that they were written by the same method. A refusal that
distinguished them would answer a caller probing for which header is checked.
`MessageDigest.isEqual` over `String.equals` is the same instinct one layer
down, and its limit is written where it is used: it still returns early when the
lengths differ, so the secret's length is not hidden.

## The rule is on the prefix, and it is an `AuthorizationManager`

`.requestMatchers("/internal/**").access(new SharedSecretAuthorization(secret))`,
above `anyRequest().denyAll()`.

**The prefix rather than the endpoint**, so that a second internal endpoint
inherits the rule instead of having to remember it. The one that forgot would be
the open one, and it would look exactly like the ones that did not.
`guardsThePrefixRatherThanTheHandler` asks for a path under `/internal` that has
no handler at all — a bare `GET`, so that not even the method matches the one
mapping the prefix has — and asserts the refusal. That is the version of the
claim that would fail if the rule were ever moved onto the controller.

**An `AuthorizationManager` rather than a filter of our own.** The rule then sits
on the chain beside the permits it qualifies, so the whole policy reads in one
method, and it runs at the point that already knows how to turn a refusal into a
response. A filter would be a second place authorization happens, and a reader
of `SecurityConfiguration` would have no way to know it existed.

It grants nothing and authenticates nobody. Presenting the secret means a
request may proceed, not that this application knows which Check service made
it; nothing downstream learns who called. That distinction is what the deferred
*service identity* work is about, and this class is deliberately not a step
towards it.

**A blank configured secret is a startup failure, not a permissive rule.** With
`payments.internal.shared-secret` empty, every request carrying an empty header
would pass, and the endpoint would be open while the configuration still said it
was guarded. `SharedSecretAuthorization` refuses to be constructed.

**The property keeps a development value rather than being absent.** Removing it
was considered and rejected: with no default anywhere, a deployment that forgot
the secret would fail at startup instead of running with a value that is in the
repository, which is the stronger guarantee. What it costs is that `./mvnw
spring-boot:run` no longer works on a clean clone without setting a variable
first, and that the two tests reading the property through `@Value` would need a
test property source of their own — a build where the checked-out state does not
run is a worse first five minutes than the one thing in
`application.properties` a deployment is told it must replace. The comment
beside the key is that instruction, and it is the only line in that file that
carries one.

### Two method parameters rather than two beans

`securityFilterChain` takes the secret and the `JsonMapper` as parameters of the
`@Bean` method. The alternative — a `@Bean` for the authorization manager and
another for the denial handler — reads tidier and costs a slice test: with the
collaborators as beans, `@Import(SecurityConfiguration.class)` no longer brings
the whole policy, and a `@WebMvcTest` of an internal endpoint would have to
rebuild the chain or assert the mapping against Spring Boot's default one. That
is the version of the test that passes while the one authorization rule this
application has is silently absent.

For the same reason `SecurityConfiguration` is now `public`. It is the only
reason, and it is written on the class.

**Spring Boot registers no `JacksonJsonHttpMessageConverter` bean** — Spring
MVC's converters are assembled inside its own configuration — so the first
attempt at this failed to start the context. `DeniedAsAProblemDocument` builds a
converter over the auto-configured `JsonMapper` instead. The mapper is the part
that has to match the rest of the API; the converter instance is not.

### The request cache would have created a session

`ExceptionTranslationFilter` saves the denied request before calling the entry
point, and `HttpSessionRequestCache.saveRequest` calls `getSession()` — which
issues the session cookie `SessionCreationPolicy.STATELESS` and the CSRF
decision both assume is absent. `.requestCache(RequestCacheConfigurer::disable)`
turns it off. The cache exists to replay a request after a login this
application does not have.

`aDenialIssuesNoSessionCookie` in the end-to-end test is what holds it: a
different claim from `SecurityChainTest`'s, which is made about a request the
chain *permits*.

## Three refusals, three URNs

| Refusal | Status | URN |
|---|---|---|
| No Transfer with that identifier | `404` | `urn:problem:not-found` |
| A Check that Transfer's ledger has no row for | `404` | `urn:problem:check-not-required` |
| A Verdict for a Transfer that already finished | `409` | `urn:problem:transfer-not-pending` |

**`check-not-required` gets a URN of its own rather than sharing `not-found`,**
although both are `404` and both mean "the path names nothing". §18 makes the
URN the only member a client may branch on. Sharing one would leave "no such
Transfer" and "not a Check of this Transfer" tellable apart only by which
properties happened to be present, which is branching on something else — and
the two want different action from an operator: one is the wrong identifier, the
other is a reporting service running against stale configuration.

**A late Verdict is `409` and carries `transferStatus`.** The request is
well-formed and understood, and it is the Transfer's state that refuses it —
the same reading of the status the two idempotency conflicts use, and the reason
this needs a URN of its own rather than joining them. The status it found is
what turns the refusal into news: a Check service redelivering the Verdict that
settled a Transfer learns its report landed, and one answering a Transfer the
reaper expired learns it did not. It is the field
[ticket 20](20-record-verdict.md) put on `TransferNotPendingException` for
exactly this.

No `Retry-After`, and its absence is machine-readable on
[ticket 14](14-request-transfer-endpoint.md)'s precedent: a Transfer never leaves
a terminal status, so a caller that retried this would retry for ever.

**The handlers sit on the controller**, not in a `@ControllerAdvice`, on ticket
14's reasoning unchanged — an advice would have to out-order
`ProblemDocumentAdvice`, whose handler for `Exception` would otherwise answer
each of them as a `500`. What did move is the `404` for an unknown Transfer:
`UnknownTransferException` is now raised on two paths, so `TransferProblems`
holds the one rule about it. Two controllers writing their own would be two
copies, and the copy that was not edited would be the one a client had trusted.

## `200` with a receipt, not `204`

The response carries `{transferId, check, verdict, transferStatus}`.

Nothing is created at a URL a caller could then fetch, so not `201`. And there
is something to say, so not `204`: a Check service that reports one of two
Checks learns its Verdict is not yet the last word, and one that reports the
last learns that money moved. Under `204` it would have to poll the Transfer
back to find out, which is a request made because the previous response withheld
something it already knew.

`transferStatus` is the same member `TransferNotPendingException` puts on the
wire when the report was too late, so "where is this Transfer now" is answered
in one vocabulary whether the report landed or was refused.

The report is echoed back rather than assumed. This endpoint is called by
machines with retries and logs, and a receipt naming only a status would not say
which of several in-flight reports it was the receipt for.

**The request body carries the Verdict and nothing else.** The Transfer and the
Check are the address — together they name the one outstanding ledger row being
answered — and repeating either in the body would let a request name one
Transfer in its URL and another in its payload, leaving something to decide
which to believe. `CreateTransferRequest` deletes the same class of bug by
leaving the Currency out. `VerdictReport` is still a record with one member
rather than a bare enum on the wire, so that a reporting service with a reason,
a correlation identifier or a decision timestamp has somewhere to put it.

## The endpoint is in the published document, and says so

`/internal/**` is in `/v3/api-docs` on purpose, unlike the mock FX provider,
which is `@Hidden` because it stands in for somebody else's API
([ticket 24](24-mock-fx-provider.md)). This one is ours, and a Check service
being integrated is the reader the document exists for. What it cannot guess
from the shape of the request is that these operations take a credential, so
`OpenApiConfiguration` publishes an `apiKey` scheme in the header and attaches a
requirement to the operations under `/internal` — which is also what puts the
Authorize box in Swagger UI.

**Per-operation rather than document-level.** A document-level requirement would
describe the whole API as needing a secret the public half does not, sending an
integrator to look for a credential nobody will issue them.
`InternalSecretReachesTheDocumentTest` asserts the negative half for that
reason.

**Selected by path prefix rather than by an annotation on the controller,** so
that the document runs the same rule the chain does: both name `/internal`, and
an endpoint added under it is documented as guarded because it *is* guarded
rather than because somebody remembered the annotation.

`apiKey`, not `http`/`bearer` — the same fact as the `403` above, said to a
different audience. There is no registered HTTP authentication scheme for a bare
shared secret, and describing it as `bearer` would send an integrator to send a
header this application does not read.

The header name is a literal in `OpenApiConfiguration` and again in
`SharedSecretAuthorization`, and `private` in both. That is `X-Idempotency-Key`'s
existing precedent in `SecurityConfiguration`: a constant shared across packages
would make the description of the API depend on the slice that enforces it. The
tests write it out a third and fourth time on the same reasoning the paths are
written out under — a constant a test read from the class it is testing would
follow a rename silently instead of failing on it, which for a header name on the
wire is a breaking change for every Check service already integrated.

## What the new URNs cost the frontend

Three additions to `ProblemType` make `frontend/src/api/schema.gen.ts` stale,
and regenerating it breaks `frontend/src/api/problem.ts`, whose `MESSAGES` table
is `Record<ProblemType, ProblemMessage>` and exhaustive by design. That is the
cost [ticket 37](37-playwright-harness.md) established should be paid by the
ticket that causes it, and it is the point of the table being exhaustive: a URN
the backend can emit and the app has no wording for is a blank screen.

All three are `retryable: false`, and only one is reachable from the browser at
all:

- **`forbidden`** shares `APP_SENT_SOMETHING_WRONG` with the three malformed-
  request URNs. The public API takes no credential, so the only way this app
  meets a `403` is by asking for a path outside `/api` — a wrong base URL, or a
  route it should not have built. That is a malformed request by another name,
  and no credential entered on any screen would fix it.
- **`check-not-required`** and **`transfer-not-pending`** are answered only by
  the internal endpoint, which a browser never calls. They share a message that
  says so rather than pretending there is something on the screen to correct.

## Rejected alternatives

**Rejected — an empty `403`.** See above; it was ticket 04's answer and it was
right until the population of callers changed.

**Rejected — `401` with a bespoke `WWW-Authenticate`.** Malformed under RFC
9110, or a lie about which scheme is accepted.

**Rejected — a servlet `Filter` for the secret.** Authorization would then
happen in two places, and only one of them is where a reader looks.

**Rejected — checking the header in the controller.** It would run after
argument binding and validation, so a malformed body would be answered before
the caller was refused; the endpoint would leak the shape of its request to
anyone. And the rule would guard one handler rather than the prefix.

**Rejected — reusing `urn:problem:not-found` for an unknown Check.** Two facts
under one URN, tellable apart only by inspecting properties, against §18.

**Rejected — `422` for a late Verdict.** The request is understood and
processable in itself; what refuses it is the Transfer's state, which is the
distinction `409` already carries for the two idempotency conflicts.

**Rejected — `204` on success.** A Check service could not tell "recorded,
still waiting" from "recorded, and the money moved" without a second request.

**Rejected — the Transfer or the Check repeated in the request body.** Two
places to name one thing, and a rule needed for when they disagree.

**Rejected — hiding `/internal` from the published document.** The mock FX
provider is hidden because it is not our API. This one is, and the Check service
integrating against it is the document's reader.

## What this ticket deliberately did not build

**A separate port for internal traffic** stays deferred, and its reasoning is
now concrete rather than hypothetical: the secret is a real rule with a real
test, and a second connector would be defence in depth on top of it rather than
a replacement for it. See [deferred.md](../deferred.md).

**Service identity.** Nothing learns *which* Check service called. The secret is
one shared value, so it cannot be rotated per caller or revoked for one of them,
and an audit trail cannot name the reporter. That is a different feature, and
the shape of this one does not obstruct it.
