# 34: Turning a problem document into something readable

**What to build:** A pure function mapping a problem document onto what the UI needs:
a title, a body, and **whether retrying will help**. That last field is the point — an
operator seeing an error should know whether to try again or to change something.

It branches on the `type` URN and on nothing else. The backend made that URN the sole
discriminator precisely so no client has to reconcile two fields that can disagree; the
frontend must not reintroduce the problem by also inspecting status codes.

The URNs come from the generated types, so a URN the backend stopped emitting — or a new
one it started emitting — shows up as a type error rather than as a silently unhandled
case.

A table test over every URN the backend can emit, including the two `409`s that mean
opposite things: request-in-progress is retryable, key-reused is never.

**Blocked by:** 32

**Status:** done

- [x] A pure function maps a problem document to title, body and retryability
- [x] It branches on the type URN only
- [x] Both `409` URNs are handled and have opposite retryability
- [x] An unrecognised problem document degrades to a readable generic message
- [x] A table test covers every URN the backend emits, sourced from the generated types

## Comments

**The one member the module is allowed to read is enforced, not intended.**
`problem.test.ts` reads its own subject's source through Vite's `?raw` — ticket 33's
technique — and asserts the word *status* does not appear in it at all. "Branches on the
URN only" erodes one plausible edit at a time, and `if (problem.status === 409)` to tell
the two `409`s apart is the exact edit §18 exists to prevent while looking reasonable in
a diff. The price is that the module may not say the word either, so its doc comment
calls it "the response code". A runtime half sits beside it: every URN goes through the
function with five different status values and the answer must be identical, which
catches a branch the source-level test cannot see.

**`retry.ts` reads a status code, and that is not this rule being broken.** It answers a
different question — should the query client silently re-send *now* — over transport
errors that often carry no document at all. The two deliberately disagree on
`request-in-progress`: `retry.ts` says no, because the response carries a `Retry-After`
it must not race with a backoff timer of its own, while `problemToMessage` says yes,
because the operator asking whether to try again in a minute is owed one. A single flag
serving both callers would have to lie to one. Design decision 34 has the table.

**The argument is `unknown`, which the ticket did not ask for and the alternative was
worse.** Typing it `ProblemDocument` reads better and pushes `(await response.json()) as
ProblemDocument` onto every call site — a shape nobody checked, asserted, which is the
hand-written trust ticket 32 exists to delete rather than relocate. The exhaustiveness
requirement survives it: it lives in the message table's `Record<ProblemType, …>`, so a
URN added or removed on the backend is a compile error either way, while the `unknown`
parameter covers the case no type can — a URN from a backend newer than this build.

**Two things the checkboxes did not name and the copy had to settle.** The wording is
the module's own rather than the document's `title` and `detail`, which are Spring's
status reason phrase and a sentence written for whoever is reading the response; the
accepted cost is that a `not-found` body no longer says *which* Transfer. And the
unrecognised fallback advises retrying, which is a choice about which way to be wrong:
the Idempotency Key is held across attempts, so a needless retry costs one click, while
advising against one strands an operator on a failure that had already cleared.

**Caught while writing the copy:** the `internal-error` body first said the request had
already been attempted more than once — true of a query, which `retry.ts` gives three
attempts, and false of a `POST`, which gets one. The function is not told which it is
looking at, so every sentence in the table has to hold for both.

**A claim this ticket made about code that does not exist yet.** The
`fx-provider-unavailable` body says no money was moved. §15 and §27 make that true of
the design — a Transfer that never got an Exchange Rate never reserved anything — but
ticket 25 has not built it. Recorded in decision 34 so that ticket knows it owns the
sentence.

**The two-axis review changed four things.** One was a wrong answer rather than a wrong
word: `client-error` shared the copy of the three "this app formed the request wrongly"
refusals, and `ProblemDocumentAdvice.typeOf` makes `CLIENT_ERROR` the `default ->` arm
for every 4xx the switch does not name — so it covers `408` and `429`, which *are*
retryable, and `403`, which is not about how a request was formed. It now says the
service refused without naming a reason, `retryable: true`, which is what the backend's
own Javadoc anticipated a client would do with it.

Two were CONTEXT.md's `_Avoid_` lists showing up in operator-facing copy: a reused key
belonged to *"a different payment"*, where the domain's word is Transfer, and the FX
body blamed *"the rate provider"* rather than the exchange rate provider. Both are
strings an operator reads, which is exactly where the shared vocabulary is supposed to
surface. The fourth was the test's header comment restating a paragraph of decision 34
that AGENTS.md allocates to the record; trimmed to a pointer.

**One gap the review found in the fifth checkbox, and closing it needed a test rather
than an answer.** "Sourced from the generated types" held only through
`Record<ProblemType, …>`, and `npm test` is `vitest run` with no type-check in front of
it — so a URN the backend had grown failed `npm run build` and passed `npm test`. The
test now reads `schema.gen.ts` through `?raw` and pins the URN list against the module's
table, so the test command alone catches it.

**Two duplications answered rather than changed.** `recognisedTypeOf` here and
`httpStatusOf` in `retry.ts` narrow an `unknown` the same way, and this file's
source-assertion block is a second copy of `money.test.ts`'s. Both are two instances,
and a shared helper that two callers pick a home for is a guess; ticket 35 adds the
third of each, which is where the extraction earns itself.
