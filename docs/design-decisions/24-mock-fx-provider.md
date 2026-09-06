# Ticket 24 — the stand-in Exchange Rate provider

§28 settled that the mock provider is a real HTTP endpoint inside the application
and listed five Spring mechanisms that keep it out of our cross-cutting layers.
Building it found a sixth, found that one of the five names a package this
codebase does not have, and found that another has nothing to register yet.

Touches §28 of the [initial decisions](00-initial-decisions.md), and §30's
package-by-feature layout by way of it.

---

## §28's scoped advice names a package package-by-feature never creates

The section says the mock's errors come from
`@RestControllerAdvice(basePackages = "…payments.api")`. There is no
`payments.api` package and there was never going to be: §30 organises by feature,
so this application's controllers are spread across `accounts`, `transfers` and
`transfers.checks`, and every slice added later adds another.

Scoping `ProblemDocumentAdvice` to that list would work and would be the wrong
trade. The advice is what gives every failure in the system its `type` URN, and
the failure mode of an enumerated list is that a new slice is not on it —
silently, with the symptom being an error document that is missing the one member
design decision 18 lets a client branch on. That is a load-bearing default traded
for a list someone has to remember.

**What the mock does instead: `@ExceptionHandler` methods on the mock controller
itself.** A handler declared on a controller is resolved before any
`@ControllerAdvice` in the application, so the mock keeps its own error shape
without anything having to be ordered, and `ProblemDocumentAdvice` stays global
for everything that really is ours. This is the same answer ticket 14 reached for
a different reason — there, an advice that had to win a sort turned a `422` into
a `500`.

**The edge this leaves open, deliberately.** A controller-local handler only sees
exceptions raised once a handler method has been chosen. A request to a path
under `/mock/**` that maps to nothing, or a method the mapping does not allow, is
refused by the dispatcher before that point and is answered by
`ProblemDocumentAdvice` as one of our problem documents. It is left that way
because those are not the provider's errors — they are the absence of a provider
endpoint — and closing them costs the enumerated package list above. Every status
the provider itself produces (`200`, `503`, `404`, `400`) carries its own shape,
which is what `MockProviderStaysOutOfOurCrossCuttingLayersTest` and
`MockProviderIsAsFlakyAsConfiguredTest` assert.

## The sixth layer: the published OpenAPI document

§28 lists security, error handling, servlet filters, CORS and the package graph.
It does not list `/v3/api-docs`, which is a layer of ours in exactly the same
sense — and the only one whose leak outlives the process.

Design decision 26 generates the frontend's types from a **running backend**
(`npm run api-types` reads `http://localhost:8080/v3/api-docs`). So a developer
who ran with `mock-fx` active and regenerated would commit `/mock/fx/rates` into
`schema.gen.ts` — a third party's endpoint in our API's types, carrying our
`ProblemDocument` as its error response, because `OpenApiConfiguration` attaches
that to every operation in the document.

Closed with `@Hidden` on the controller. It sits on the mock rather than as a
`springdoc.paths-to-exclude` property on the application for the same reason the
security bypass does: the isolation is the mock's to declare, and it should
disappear when the mock does.

## §28's filter bullet has nothing to register

The section calls for a `FilterRegistrationBean` with
`addUrlPatterns("/api/*", "/internal/*")` so that logging and MDC filters never
touch the mock. This application registers no logging or MDC filter, so there is
nothing to give URL patterns to, and no test here can claim otherwise — a green
assertion over zero filters is not a verified exclusion, it is a vacuous one.

Building one so that the mock could be excluded from it would be inventing a
cross-cutting layer inside a ticket about an FX mock. The constraint any future
filter inherits is recorded in [deferred.md](../deferred.md) instead, where the
person who adds one will be reading.

The one filter this application *does* add — Spring Security's chain — is
excluded, and that exclusion is verified by effect rather than by configuration:
the chain sets `X-Content-Type-Options` on everything that passes through it, so
a mock response that carries the header would mean the bypass had become a
`permitAll`.

## The CORS exclusion needed a second assertion to be worth anything

The obvious test is a cross-origin preflight to `/mock/fx/rates` that comes back with no
`Access-Control-Allow-Origin`. It passes, and on its own it proves nothing.

CORS in this application is applied by exactly one thing: the security chain's filter,
via `.cors(withDefaults())` on the chain and a `CorsConfigurationSource` bean. The
provider's paths are bypassed out of that chain. So the header is absent because the
filter never runs — and it would still be absent if someone registered `/mock/**` on the
CORS source tomorrow. The assertion is entailed by the security bypass sitting next to
it, and cannot fail for the reason its name gives.

This is the same vacuous green the filter bullet above is deferred to avoid, and it is
easier to miss here because there *is* a real assertion running. It is closed by asking
the `CorsConfigurationSource` what policy it holds for a path under the provider and
requiring `null` — configuration read directly, which this codebase otherwise avoids,
because here the configuration is the thing that can drift and the observable effect is
already pinned by something else.

Measured rather than argued: registering `/mock/**` on the CORS source turns
`mapsNoCorsPolicyOntoTheProvider` red and leaves
`allowsNoCrossOriginAccessToTheProvider` green, which is the whole finding in one run.

## Rejected — quoting from a table of pairs

Six hand-written rates can disagree with each other: an `EUR/HUF` and a `USD/HUF`
that do not agree with the same table's `EUR/USD` produce a converted amount that
is off by a remainder, and the first place anyone would look is ticket 07's
rounding. `QuotedRates` holds one figure per currency against a pivot and divides,
so cross-rate consistency is a property of the shape rather than of the numbers
having been typed carefully.

## The latency is applied before the failure is decided

So a `503` is as slow as a `200`. A provider that answered its errors promptly
would let a client's read timeout and its retry budget be tuned independently,
which is not the situation an unreliable third party puts you in — and it would
leave ticket 25's timeout with only the success path to fire on.

## Observed — Spring Security warns about the bypass at startup

```
WARN o.s.s.c.a.web.builders.WebSecurity : You are asking Spring Security to ignore
PathPattern [/mock/**]. This is not recommended -- please use permitAll via
HttpSecurity#authorizeHttpRequests instead.
```

The advice is right for endpoints that are ours and wrong for this one, which is
ticket 04's rejected alternative read in the other direction: there,
`ignoring()` on `/api/**` was rejected *because* it forgoes the security response
headers. Here that is the point. The warning appears only under a profile whose
whole purpose is to pretend, so it is left to print rather than silenced.

## What went to the README instead

The README carries the profile, the two dials and what turning each of them does,
since that is what someone running the thing needs. It also carries the
consequence §28 names — that the application now calls itself over HTTP, which is
what makes `spring.threads.virtual.enabled` load-bearing rather than a nicety.
