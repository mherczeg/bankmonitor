# Ticket 34 — the frontend end of the sole discriminator

§18 made the `type` URN the only member of a problem document a client may branch on,
and §24 named `problem.ts` on the extraction list with the signature it should have:
`problemToMessage(problem): { title, body, retryable }`. This ticket built it.

The backend side of that rule is finished — [ticket 05](05-problem-detail-contract.md)
picks the URN from one place and [ticket 32](32-openapi-type-generation.md) publishes
the vocabulary — so the decisions left here were about what the client does with it:
whose words the operator reads, what `unknown` failures degrade to, and how the rule is
kept from eroding one `if` at a time.

Touches §18 and §24 of the [initial decisions](00-initial-decisions.md).

---

## The copy is written in the module, not taken from the document

`problemToMessage` reads exactly one member of what it is handed. The `title` and `body`
it returns are the module's own words, keyed on the URN; the document's `title`,
`detail` and `status` are all ignored.

**Rejected — showing the document's `title` and `detail`.** The obvious answer, and RFC
9457 does say `detail` is a human-readable explanation. It is not written for *this*
human. Spring's `title` is the status reason phrase — `"Conflict"`, `"Bad Request"` —
which tells an operator nothing they can act on, and `detail` is a developer's sentence
about the request: `"Invalid request content."`, `"Method 'PATCH' is not supported."`,
`"Failed to read request"`. Ticket 05 already found one of these bad enough to replace
on the server (`"No static resource api/nope."`), which is the general case showing
through: the document explains the failure to whoever is reading the response, and the
screen has to explain it to whoever pressed the button.

**Consequence, accepted:** a `not-found` body says *"What was asked for does not
exist"*, where `detail` could have said *which* Transfer. That specificity is real and
it is lost. It is recoverable when a screen wants it — `detail` is still on the document
the caller holds — and the reason it is not woven in here is the next section.

**The one server-supplied member a screen does use is `errors`,** and it does not come
through this function. Per-field messages belong against the fields they name (tickets
39 and 40), not concatenated into a paragraph. The `validation-failed` body therefore
tells the operator to correct the flagged fields rather than repeating what the flags
say.

## `retryable` is advice to a person, and `retry.ts` is a rule for a machine

Two modules in `api/` now look at a failure and answer a question with "retry" in it,
and they answer different questions.

| | `retry.ts` | `problem.ts` |
|---|---|---|
| Asked by | the query client, automatically | a person, reading a screen |
| Branches on | the HTTP status of a thrown error | the `type` URN |
| Sees | any transport failure, document or not | a parsed response body |
| Answers | should this be re-sent now, silently | would trying again ever help |

**They disagree on `urn:problem:request-in-progress`, and that is the point.** `retry.ts`
returns `false` for a `409` because the response carries a `Retry-After` the client is
meant to obey rather than race with a backoff timer. `problemToMessage` returns
`retryable: true` for the same response, because the operator asking "is this worth
trying again in a minute" is owed a yes. A single "retryable" flag serving both callers
would have to lie to one of them.

**This is also why `retry.ts` reading a status code does not contradict this ticket's
second requirement.** The prohibition is on branching on two members of *one document*,
which is where two discriminators drift apart. `retry.ts` is upstream of any document —
it runs on errors that never had one, a dropped connection or a gateway's own `502` —
and a status is all it has.

## The argument is `unknown`, because that is what a response body is

`problemToMessage(failure: unknown)`. Typing it `ProblemDocument` would read better and
would push a cast to every call site — `(await response.json()) as ProblemDocument` —
which asserts a shape nobody checked. That is precisely the hand-written trust
[ticket 32](32-openapi-type-generation.md) exists to delete, and moving it from a type
alias into a cast does not delete it, it hides it.

So the module is the boundary: it takes whatever a failed request produced and returns
three fields a screen can always render. What arrives is genuinely unruly — a problem
document, a gateway's HTML error page, a `TypeError: Failed to fetch`, a `null` from an
empty body — and every one of them has to end up as something readable.

**The exhaustiveness the ticket asks for survives this.** It lives in the table's type,
`Record<ProblemType, ProblemMessage>`, not in the parameter: a URN the backend adds
fails the build on the next `npm run api-types`, and one it removes fails as an excess
key. The `unknown` parameter is about a URN arriving from a backend *newer than this
build*, which no type can prevent and only a runtime fallback can survive.

**`Object.hasOwn`, not `in`.** `'toString' in MESSAGES` is `true`, so a body claiming
`type: "toString"` would be "recognised" and hand back a function off the prototype
chain. It is a one-character class of bug that only exists because the lookup key comes
off the network, and the test pins it.

## An unrecognised failure advises retrying

`UNRECOGNISED_PROBLEM` is `retryable: true`, which is a deliberate choice about which
way to be wrong.

Advising a retry that cannot succeed costs an operator one click. Advising against a
retry that would have succeeded strands them on a failure that had already cleared. The
asymmetry only holds because of ticket 35: the Idempotency Key is held across retries,
so a second attempt at the same intent is the mechanism working rather than a second
Transfer. On a screen with no such key, the safe default would be the other one.

It is also the empirically right answer for what actually reaches here. A URN this build
has never heard of means a backend newer than the browser tab, which a reload fixes;
everything else in the bucket — the gateway page, the dropped connection — is transient
by nature.

**`urn:problem:client-error` keeps its own entry and gives the same advice**, which is
this ticket's one correction. It first shared the copy of the three refusals below —
*"the refusal is in how the request was formed"*, `retryable: false` — and review checked
that against `ProblemDocumentAdvice.typeOf`, where `CLIENT_ERROR` is the `default ->` arm
for **every** 4xx the switch does not name, an unresolvable status included. So it covers
`408` and `429`, which are the retryable client errors, and `403`, which is not about how
a request was formed at all. The copy asserted something the URN does not know.

The backend's own Javadoc had already said what to do — *"a client shows an unrecognised
type generically anyway"* — so it now reads as a refusal without a stated reason, with
`retryable: true` on the same "which way to be wrong" argument as the fallback. It stays
a separate entry rather than pointing at `UNRECOGNISED_PROBLEM` because the two are not
the same event: this is the service answering, and the wording can say so.

## Three URNs share one message, and still get three entries

`malformed-request`, `unsupported-media-type` and `method-not-allowed` are one situation
to the person reading them: this app formed a request the service refused, and nothing
on the screen is wrong. They point at a shared `APP_SENT_SOMETHING_WRONG` constant, so
the sameness is deliberate and visible rather than three copies drifting apart.

**Rejected — a `default` branch covering them.** A default is how the *next* URN
silently inherits advice nobody chose for it, which is the failure the exhaustive
`Record` was picked to prevent. Sharing a value costs one line per key and keeps the
compiler's list complete.

## The rule is asserted, not trusted

`problem.test.ts` carries three assertions about its own subject's source, read through
Vite's `?raw` — the technique [ticket 33](33-money-format-module.md) introduced:

- the import list is exactly `['./types']`,
- the word *react* does not appear (§24's rule),
- **the word *status* does not appear.**

The third is this ticket's second requirement made mechanical. "Branches on the URN
only" is the kind of rule that erodes one plausible edit at a time — `if (problem.status
=== 409)` to tell the two `409`s apart is the exact edit §18 exists to prevent, and it
would look reasonable in a diff. A rule that a test enforces cannot be broken by
looking reasonable.

**Its price is that the module may not say the word either**, so the doc comment calls
it "the response code". That is a real cost — the module cannot name the thing it is
avoiding — and it buys an assertion no spelling walks around, which a regex aimed at
`problem.status` would not be. The test says so where a reader of it will be confused.

There is a runtime half beside it: every URN is put through the function with five
different status values, including implausible ones, and the answer must be identical.
That catches a branch on a code the source-level test cannot see, such as one arriving
through a helper.

**The same `?raw` trick closes a gap in the ticket's fifth requirement.** "Sourced from
the generated types" was satisfied only by `Record<ProblemType, …>`, and `npm test` is
`vitest run` with no type-check in front of it — so a URN the backend had grown reached
a red build but a green test run. The test now reads `schema.gen.ts` itself and pins the
URN list against the module's table. It cannot go vacuous: a regex that stopped matching
yields an empty list against ten entries.

This is the second copy of ticket 33's source-assertion block. It is left duplicated
rather than extracted, because a shared helper needs a home and two callers do not
choose one; [ticket 35](../../.scratch/global-payment-service/issues/35-idempotency-key-module.md)
adds the third, and that is when it becomes a test helper rather than a pattern.

## What is not settled here

**The FX body claims no money was moved.** §15 fixes the Exchange Rate at request time
and §27 fails the request when the provider will not answer, so a Transfer that never
got one never reserved anything. That is true of the design as written and it is a claim
about code [ticket 25](../../.scratch/global-payment-service/issues/25-exchange-rate-client.md)
has not built yet. If the Exchange Rate call ever moves after the reservation, this
sentence becomes wrong in the most alarming direction, and it is named here so that
ticket knows it owns it.

**The copy has to hold for a query and for a mutation both**, because the function is
not told which. The `internal-error` body originally said the request had already been
attempted more than once — true of a query, which `retry.ts` gives three attempts, and
false of a `POST`, which gets one. It says nothing about attempts now.
