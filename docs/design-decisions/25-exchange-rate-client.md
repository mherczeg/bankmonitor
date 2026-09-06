# Ticket 25 — the Exchange Rate client

§27 settled the resilience policy — both timeouts, a bounded retry over `5xx` and
connection failures, never over `4xx`, no cache and no breaker — and §28 settled that the
thing on the other end is a real HTTP endpoint reached over a configurable base URL. Both
were written before the framework was opened. Building against Spring Framework 7.0.9 and
Boot 4.1.1 found that the retry machinery throws away the one object that knew how many
attempts were made, which forces the client into two beans rather than one; that neither
retry setting can hold its default where every other setting in this application holds
its; that §28 wrote the property's name without the prefix every property here carries;
that §28's "a real provider is a property change" holds for a provider speaking this wire
shape and not for one speaking another; and that the read-timeout gap
[deferred.md](../deferred.md) records is both narrower than it was written and precisely
explicable in a way it was not.

It also closes a deferral left by [ticket 07](07-conversion-function.md): `ExchangeRate` is
born here, one ticket after the shape was parked, and it turns out to claim less than the
concept's name suggests.

Touches §15, §27 and §28 of the [initial decisions](00-initial-decisions.md).

---

## The `ExchangeRate` type ticket 07 deferred is born here, and it claims less than its name

[Ticket 07](07-conversion-function.md) parked the type for "25 or 26, once there is
something real to model", on the grounds that ticket 25 is what defines "whether a rate
carries its pair, its timestamp and its validity window", and that inventing the shape a
ticket early would fix it against a guess rather than against the port. That was the right
call, because the guess would have been wrong: **the provider quotes a pair and a number
and nothing else.** No as-of timestamp, no validity window, no source.

So `fetchedAt` is not read off the wire. It is read from this application's injected
`Clock` at the moment the response arrives, which makes it a record of **when we learned
the rate, not when the market set it** — the weaker of the two claims, and the only one
the wire supports. Anything stronger would be this application inventing a fact about
somebody else's data, and the place it would be believed is an audit trail.

The pair travels with the number for the same reason the record exists at all: 395 is a
fact about EUR against HUF and nonsense about anything else, and a caller holding a bare
`BigDecimal` can apply it to the wrong pair without any of its own code looking wrong.
§15 needs the fetch timestamp stored on the Transfer beside the rate, and since the record
already carries the pair it was quoted for, **ticket 26 stores the record whole** rather
than three columns that could disagree with each other.

One thing §15 assumes that the wire does not supply: it speaks of "the quote's validity
window" as bounded by the same clock as the check deadline. There is no validity window in
a quote. The staleness bound §15 describes still holds — an expired Transfer releases its
funds either way — but it holds because of §14's deadline alone, not because of anything
the provider said about how long its number is good for.

## Spring Framework 7 has no `@Recover`, and on exhaustion it rethrows the last attempt's exception unwrapped

Read out of `AbstractRetryInterceptor.invoke` in the 7.0.9 sources rather than recalled: it
catches `RetryException` and does `throw ex.getCause()`. The `RetryException` is the only
object in the system that knows the whole story — it carries the earlier attempts as
**suppressed** exceptions and it knows the retry count — and it never escapes the proxy.
There is no `@Recover` to hand it to, and nothing announces exhaustion. What a caller sees
is an `HttpServerErrorException`, exactly as if the first attempt had been the only one.

**The consequence is structural rather than stylistic.** Nothing at the catch site can
distinguish "the provider returned `503`" from "the provider returned `503` three times",
because the two are the same object. So the mapping of the provider's failures onto this
application's cannot live inside the retried method: the code that catches has to be code
that runs only once the retrying is over.

That code is a second bean *above* the first, and it has to be a bean rather than a second
method, because `@Retryable` is proxy-based — a call from a class into its own annotated
method never crosses the proxy and would not retry at all. So `HttpExchangeRateProvider`
sits over `RetriedQuotes`, one holding the policy and one holding the translation, and
everything reaching the `catch` in the upper one has already been retried as far as it
will be. **The two-class shape is forced by the framework, not chosen for tidiness**,
which is worth writing down because it reads like exactly the sort of split a later reader
would helpfully collapse.

## The attempt count in the failure is read from the policy, not observed

This follows from the paragraph above: the object that knew the real number was discarded
by the interceptor before this application saw anything. So
`ExchangeRateUnavailableException` reports `maxRetries + 1` — a figure derived from the
configured policy rather than counted from what happened.

It is accurate exactly as long as the two readers read the same value, which is why they
are made to read **literally the same property**. `payments.fx.max-retries` is bound onto
`ExchangeRateSettings.maxRetries`, and the same key is spelled once as
`ExchangeRateSettings.MAX_RETRIES_PLACEHOLDER` for the annotation's `maxRetriesString` to
resolve. The
number is deliberately not passed to the exception from somewhere closer to the call
either: a second copy travelling alongside could be right about a policy nobody is
running, which is worse than being derived.

## `maxRetries` cannot come from a `@DefaultValue`, so every default moved to `application.properties`

`@Retryable`'s `maxRetriesString` and `delayString` exist precisely so the two numbers can
be configured, and they accept `${…}`. But an annotation attribute is a compile-time
constant, and a placeholder in one is resolved against the `Environment` — which has never
heard of a `@DefaultValue` written on a bound `@ConfigurationProperties` record. A default
declared on `ExchangeRateSettings` would therefore be **invisible to the annotation**, and
a second one written inline into the placeholder (`${payments.fx.max-retries:2}`) is
exactly the drift the single-copy rule exists to prevent — two defaults, in two
mechanisms, for one number.

So `ExchangeRateSettings` binds with no fallbacks at all, and all five defaults sit in
`application.properties`, which is the one place both readers look. **This is a deliberate
departure from the rule `FlakinessSettings` states one package away** — "the defaults live
here rather than in a properties file so there is one copy of them" — and it is the same
rule reaching the opposite conclusion for a case it did not anticipate: a setting with two
readers of different kinds. What matters is that there is one copy; which file holds it is
downstream of who can see it.

The cost is worth naming rather than hiding. A deployment that deletes one of those lines
now **fails at startup** — the placeholder has nothing to resolve against and the record
has no fallback to bind — instead of quietly retrying a different number of times than the
README says it does. Given that the alternative failure is silent, this is the one to
have.

## §28 named the property `fx.base-url`, and every property in this application is under `payments.`

§28 writes "the client reads `fx.base-url` from configuration". There is no such property
and there was never going to be one: `payments.cors.allowed-origins` and
`payments.mock-fx.*` were already in `application.properties` before this ticket, and
`ExchangeRateSettings` is bound at `payments.fx`. The property is **`payments.fx.base-url`**.

This is a false sentence rather than a decision this ticket contradicts, so per
`AGENTS.md` it is corrected in §28 in place and the correction is recorded here. The
evidence is one line of `application.properties` and the `PREFIX` constant beside it; the
substitution §28 describes is unaffected, and pointing this application at a paid provider
is still exactly one line.

## §28's "swapping in a real provider is a property change" is true of a provider that speaks this shape

§28 says the substitution is configuration, and the README repeated it flatly. Building the
client made the boundary of that claim visible: `payments.fx.base-url` is the only
*configurable* half. The path `/fx/rates`, the `base` and `quote` query-parameter names and
`ProviderQuote`'s three field names are compiled in, and a provider that spells any of them
differently is not reachable by editing a properties file.

This is an over-claim rather than a false sentence, so §28 is left alone and the README is
the thing narrowed: **a provider speaking this shape is one line, and a provider speaking
another shape is a second implementation of `ExchangeRateProvider`.** The correction is
worth making precisely because the second half is not a climbdown — it is what the port is
for. The timeouts, the retry policy and the mapping of the provider's failures onto this
application's are stated once behind the interface and inherited by any implementation, so
what a new provider costs is a class that speaks its wire, not a rewrite of the resilience.

Making the wire itself configurable was rejected. A path and three field names in
properties buys a provider nobody has, at the price of a startup-time contract that no
compiler checks and no test can pin, and the first provider that differs will differ in
ways a template cannot express anyway — a nested body, an auth header, a pair spelled
`EURHUF`.

## Never retrying `4xx` is implemented by not naming it, and a refusal is a different failure from an outage

The policy is `includes = { HttpServerErrorException.class, ResourceAccessException.class }`
— the provider answering `5xx`, and the connection failing or going quiet — and nothing
else. `4xx` is never retried because it is not on that list, so it propagates from the
first attempt. **The policy is expressed as an omission**, which is worth recording
because an omission is what a later edit adds to without noticing.

Three properties of Framework 7's filter make that safe, all of them in
`MethodRetrySpec`, which builds an `ExceptionTypeFilter` from the two lists and calls
`match(throwable, true)`: matching is on assignability and it **traverses the cause
chain**, so a wrapped `ResourceAccessException` still matches; `excludes` beats `includes`;
and both lists empty means retry everything. The last of the three is the trap — deleting
the `includes` line does not disable the policy, it widens it to every failure including
the deterministic ones.

**Separately, and more consequentially: exhaustion and refusal are two failures, not one.**
A budget that ran out becomes `ExchangeRateUnavailableException`, which means *try again
later*. A deterministic refusal — a malformed query, a currency the provider will not
quote — becomes an `IllegalStateException` and the global advice's `500`, because asking
again gets the same answer and there is nothing for a caller to do about it. Folding the
two into one exception would have ticket 26 answer `503` with a `Retry-After` to a request
that will never succeed: the same "confidently wrong about whose fault it is" failure
[ticket 07](07-conversion-function.md) found when a provider's rate of zero became a `422`
telling an operator their transfer was too small.

[Ticket 24](24-mock-fx-provider.md)'s stand-in answers an unknown pair with
`404 unquoted_currency`, and that response is worth naming as **unreachable in this
deployment**. `Currency` is a closed enum of exactly the three the stand-in quotes, so no
pair this application can construct is one the provider refuses. A `4xx` on this path can
therefore only mean a defect — in the query this client builds, or in a real provider that
does not quote what it was contracted to — which is precisely why the unchecked exception
is the right shape for it.

## "Refused" and "could not be read" are two sentences, because one `catch` cannot tell them apart

The first version caught `RestClientException` — the root of the hierarchy — and reported
every one of them as *the provider refused this request*. That is right for a status the
provider chose to answer with and wrong for the other thing that lands there: a `200`
whose body will not deserialise arrives as a bare `RestClientException` too, and nothing
was refused. The message would have sent a maintainer looking for a provider-side rejection
of a request the provider had in fact accepted and answered.

So the catch is split on `RestClientResponseException`, which is exactly the subtree that
carries a status: **a refusal names the status it came with, and everything else says the
answer could not be read.** Both remain `IllegalStateException` and both remain unretried —
the split is about what the failure *says*, not about what happens next, because a provider
talking a language this client cannot parse will still be talking it on the second attempt.

The status goes in the message because it is the one fact that distinguishes the refusals
from each other, and this path's `4xx` is unreachable in this deployment — `Currency` is a
closed enum of what the stand-in quotes — so anything arriving here is a defect and the
status is the first thing a maintainer will want.

**Not a bug that any test caught**, which is why it is recorded: both branches threw the
same type and satisfied the same assertion. Two tests now pin the messages apart.

## A `200` for the wrong pair is checked rather than believed

Beyond what the ticket asked for, and caused by the shape it asked for. The provider's
response echoes the pair it is quoting, and a provider quoting `USD/HUF` in answer to a
`EUR/HUF` question would otherwise settle a Transfer at a rate nobody asked about. The
number is well-formed, the status is `200`, the record constructs, the arithmetic runs and
the money moves. **It is the one provider defect on this path that has no symptom at all.**

So the echo is compared against the question and a mismatch is an `IllegalStateException`
— unchecked, and nothing catches it, for the same reason ticket 07 refuses a rate of zero:
a bug above this client is not something a caller asked for and not something a caller can
answer. `ExchangeRate`'s own constructor keeps ticket 07's zero-rate refusal one layer
earlier still, so a bad number never reaches the arithmetic that would have to interpret
it.

## Both timeouts are set without adding the artifact Boot moved them into

Boot 4 moved this furniture. `ClientHttpRequestFactorySettings`, the type every Boot 3
example configures timeouts through, no longer exists; its replacement is
`HttpClientSettings`, which lives in `spring-boot-http-client` — an artifact
`spring-boot-starter-webmvc` does not pull in.

**Rejected — adding `spring-boot-starter-restclient` for it.** It is one line of
`pom.xml` and it is the documented route, and what it buys is a record whose two fields
this application would immediately hand to the same two setters. A dependency that exists
so one configuration class can be written in the idiomatic dialect is a dependency the
reviewer has to account for.

The client builds the factory directly instead: `HttpClient.Builder.connectTimeout` for
the connect bound, and `JdkClientHttpRequestFactory.setReadTimeout` for the read bound,
both from `spring-web`, which is already here. **Those are exactly the two calls Boot's own
`JdkClientHttpRequestFactoryBuilder` makes**, and `detect()` on this classpath would
resolve to that same factory — there is no Apache HttpClient, Jetty or Reactor Netty on
it — so the dependency would have bought the same object through a longer route. The two
bounds sit on different objects because the JDK client has no read timeout of its own;
Spring's factory implements it as a watchdog over the whole response.

Its executor is a `VirtualThreadTaskExecutor` rather than the JDK client's default pool of
platform threads. Boot supplies virtual threads under `spring.threads.virtual.enabled`,
and under `mock-fx` this application is its own provider — a bounded pool of platform
threads sitting in the middle of a self-call is precisely the deadlock §28 names as the
trap the stand-in creates.

## Measured — removing `@EnableResilientMethods` turns three of the seven scripted tests red

Struck from the scripted suite's own test configuration and run: the two recovery tests
fail on the first `503`, because there is no second attempt, and the exhaustion test fails
its `gaveUp()` assertion, because no `MethodRetryEvent` is ever published. The other four —
the refusal that must not be retried, the quote for the wrong pair, the refusal that names
its status and the body that cannot be read — stay green, which is the correct result for
tests about a policy that is not running.

This is recorded because **Boot does not auto-configure it**: there is no matching entry in
`spring-boot-autoconfigure-4.1.1.jar`'s `AutoConfiguration.imports`, checked rather than
assumed. Without the annotation, `@Retryable` is an annotation nothing reads, the first
attempt is the only attempt, and **nothing says so** — no warning, no startup failure, no
difference in any signature. It is declared on `ExchangeRateConfiguration` rather than in
the root package even though its effect is application-wide, because the only retryable
method in the system is a few lines away, and a second slice wanting retry should find the
annotation by finding the code that already uses it.

## The scripted tests each enable retry themselves, so none of them can see production's copy switched off

`ExchangeRateClientSurvivesAFlakyProviderTest` builds its own context with
`@EnableResilientMethods` on the test configuration and overrides `max-retries` and
`retry-delay` in `@SpringBootTest(properties = …)`. That is what makes it fast and
scriptable, and it also means the finding above is a fact about the test's configuration
rather than about the application's. Delete the annotation from
`ExchangeRateConfiguration` and every one of those seven tests still passes.

`ExchangeRateClientGivesUpOnASilentProviderTest` exists to close that. It boots the real
application against the real stand-in with `failure-rate=1.0`, **overrides no retry
setting at all**, and asserts three attempts and one exhaustion event. It is therefore the
test that fails if `application.properties` or `ExchangeRateConfiguration` stops saying
what it says — the one place where the shipped configuration is the thing under test
rather than the scaffolding around it.

## The retry is asserted through the framework's own event, not by counting requests

`MethodRetryEvent` (`org.springframework.resilience.retry`, `@since 7.0.3`) is published
once per failed attempt, plus once more with `isRetryAborted()` when the budget runs out.
`RecordedRetries` is an `@EventListener` over it, and it is what every retry assertion in
the slice reads.

The obvious alternative is counting requests at the mock server, and it proves the wrong
thing: a number of calls happened, not that the retry policy made them. **A loop inside
the client would look identical** — same request count, same eventual answer, same green
test, and none of §27's policy in play. §27 called this event a "free win" for exactly this
reason, and the win is that resilience is asserted rather than claimed without anything
having to be mocked.

The list inside the listener is copy-on-write because the events are published on whichever
thread the retried call is running on, which under virtual threads is not the test's.

## Observed — the deferred read-timeout gap is sharper than "above the transport"

[deferred.md](../deferred.md) records that `MockRestServiceServer` "sits above the
transport, so it cannot exercise the read timeout". Building the suite made that precise,
and the precise version is stronger: the builder **replaces** the request factory it binds
to — `MockRestServiceServer`'s `injectRequestFactory` calls
`restClientBuilder.requestFactory(…)` — so the real connect and read timeouts are provably
not present in that seam rather than merely bypassed by it. The scripted tests' own
configuration makes the same point from the other side: their `RestClient` bean takes the
bound `MockRestServiceServer` as an unused constructor parameter purely to force the
binding to happen before the client is built.

**What is covered is the policy branch, and it turned out to be reachable.**
`withException(new HttpTimeoutException(…))` reaches the client as a
`ResourceAccessException`, which is the same exception a real read timeout raises, and
`retriesAConnectionThatWentQuiet` proves it is retried like a `503`. What is not covered is
the **timing**: nothing in the suite proves the client gives up at two seconds rather than
at the provider's convenience.

**WireMock was not re-added.** The deferred entry named its own reopening condition — "if
the timeout path turns out to need real coverage while writing §25's suite" — and the
condition was evaluated here and not met, because the branch that mattered is reachable
without a wire-level server. What remains deferred is a duration assertion, which is the
half that would have bought a dependency for a test about a number in a properties file.

## A self-call cannot use `RANDOM_PORT`

Both real-HTTP tests need the client's `payments.fx.base-url` **while the context is being
built**, because the `RestClient` bean is constructed from it. `local.server.port` is
published only once the server has started, which is after that. So
`WebEnvironment.RANDOM_PORT` cannot supply the address, and the arrangement is inverted: a
free port is chosen up front by `SelfHostedProvider` and the server is told to take it,
with `DEFINED_PORT` and `@DynamicPropertySource` writing both `server.port` and the base
URL from the same number.

The port is chosen **per test class rather than once for the suite**, which is not
belt-and-braces. Spring caches contexts, so the first class's context — and its running
server — is still up while the second class starts, and a shared port would collide on the
second one.

## What went to the README instead

The README carries the port, the wire shape a provider has to speak for swapping it in to
be a property change and what it costs when one does not, the
table of the five `payments.fx.*` settings with their defaults and what each one does, the
resilience policy stated once as a paragraph, and what a caller sees when the budget runs
out. That is the durable statement, and it is written to stand without this file. The
reasoning above — why the client is two beans, why the defaults are not on the record, why
a refusal is a different exception from an outage — is what stays here.

[Ticket 26](../../.scratch/global-payment-service/issues/26-cross-currency-transfers.md)
inherits three things from this file: the record it stores whole onto a Transfer, the two
exception types it has to answer differently, and the fact that
`ExchangeRateUnavailableException` is the only one of them that deserves a `503` and a
`Retry-After`.
