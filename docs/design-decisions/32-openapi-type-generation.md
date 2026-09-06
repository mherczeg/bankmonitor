# Ticket 32 — frontend types generated from the OpenAPI document

§26 planned this as a generator pointed at `/v3/api-docs`. The generator was the
easy half. What the ticket actually found is that **the document springdoc
publishes on its own is not good enough to generate from** — it describes an API
that appears never to fail and whose response members are all optional — and both
gaps had to be closed on the backend before the frontend types were worth having.

Touches §18, §24 and §26 of the [initial decisions](00-initial-decisions.md).

---

## springdoc cannot see the error contract, because no controller returns one

§18's whole design is that errors are produced by `ProblemDocumentAdvice` after a
handler has thrown, not returned by handlers. springdoc introspects handler
signatures. So the two decisions compose into a document in which
`GET /api/accounts` has exactly one outcome, `200`, and the ten problem-type URNs
appear nowhere at all — which would have left §26's headline claim, that the URNs
reach the frontend from one place, quietly unmet while everything compiled.

`OpenApiConfiguration` contributes what introspection cannot reach: a `ProblemType`
enumeration, a `ProblemDocument` shape, a `ValidationError` for the `errors`
member, and a `default` response on every operation pointing at the document.

**The enumeration is built from `ProblemType.values()`, never listed.** A list
written into that file would be the second copy of the vocabulary §18 exists to
keep single, and the copy that goes stale without anyone noticing.
`ProblemSchemaReachesTheDocumentTest` compares the published enumeration against
the Java enum, so adding a constant and forgetting the frontend is a failing test
rather than a dead branch in a client.

**`default` rather than a list of statuses per operation.** It reads like a
shortcut and is the literal truth here: the advice funnels every failure through
one shape, so there is no status for which a caller would parse something else.
Enumerating `400, 404, 409, 415, 422, 500` per operation would be a longer way of
saying the same thing, and wrong the first time an operation grows a status nobody
updated the annotation for.

**Rejected — `@ApiResponse` annotations on the controller methods.** The
conventional answer, and it puts the error contract in the place least able to
keep it: every controller, repeated per method, with the URN list restated in
annotation arguments. Ticket 09 already declined to describe the schema by hand
where springdoc could introspect it; this is the same judgement applied where
springdoc *cannot*, and the answer is one bean rather than an annotation per
endpoint.

## A response record publishes every member as optional, and that guts the point

springdoc derives `required` from constraint annotations. A request has them —
ticket 09 recorded `required: ["currency","openingBalanceMinorUnits"]` coming from
`@NotNull` — but **a response is never validated**, so `AccountResponse` published
a schema with no `required` at all and `openapi-typescript` emitted every member
optional.

That is not a cosmetic gap. The ticket's stated purpose is that a mock the backend
would never send fails the build; with every member optional, a fixture of `{}`
type-checks as an `Account` and the browser tests go back to proving that the
frontend handles shapes their author invented.

The record says so itself, with one `@Schema(requiredProperties = ...)` at the
type, and `AccountSchemaReachesTheDocumentTest` compares the published list against
the record's own components by reflection — so the annotation cannot drift from the
record it sits on.

**Rejected — `@NotNull` on the components.** Three of the five are `long`, where a
null-check annotation is meaningless, and it would put a validation annotation on a
type nothing validates.

**Rejected — a customizer marking every property of every schema required.** No
annotation anywhere, and it holds for every shape in the document today. It is a
rule that lies the first time a response has a genuinely optional member, and it
lies silently, in the document that the frontend trusts *because* nothing there is
written by hand.

**Rejected — `Required<...>` around the alias in TypeScript.** Cheapest of the
three, and it puts a hand-written claim about the backend's behaviour into the one
module whose entire proposition is that no such claim is written by hand.

## `nullable: true` is not a keyword in OpenAPI 3.1, and swagger drops it silently

springdoc 3.x emits `openapi: 3.1.0`. A validation error's `field` is null for a
violation of a class-level rule — the member is present and null, rather than
absent — and `Schema.setNullable(true)` produced a plain `"type": "string"` in the
output, with no warning. The 3.1 spelling is a union of types, `setTypes(["string",
"null"])`, which reaches the frontend as `field: string | null`.

Worth knowing before someone reaches for `setNullable` again: the API exists, is
accepted, and does nothing.

## `openapi-typescript` still declares TypeScript 5 as its peer

The repo is on TypeScript 6, and `openapi-typescript@7.13.0` — the current
release — declares `peerDependencies: { typescript: "^5.x" }`, so a plain
`npm install` refuses it. Resolved with an `overrides` entry in `package.json`
pinning its `typescript` to the root's, rather than with `--legacy-peer-deps`: the
flag would have to be remembered on every future install by every person and every
CI step, where the override is a fact recorded in the file that already records
the dependency. The generator itself works — the committed output is its product.

This is the same shape as §26's own ecosystem bet about springdoc and Boot 4, one
layer down, and it is recorded here so the next person who sees the peer warning
knows it was met rather than ignored.

## `schema.gen.ts` is committed, and its diff is left open in review

Ticket 31 settled the same question for `routeTree.gen.ts` and the answer is not
quite the same here, in one respect that matters.

Both are committed for ticket 31's first reason: a fresh clone type-checks with no
generator run. But the route tree is regenerated *by the build* — `tsr generate` is
the first thing `npm run build` does — and this file cannot be, because generating
it needs a running backend, which a build does not have and should not require.

So it is refreshed by hand, and the mitigation is the opposite of the route tree's:
where `routeTree.gen.ts` is marked `linguist-generated=true -diff` so a mechanical
rewrite stays out of review, `schema.gen.ts` is marked `-linguist-generated`. A
change in it is the backend's API moving underneath four screens, and it is the one
thing a reviewer of that commit has to read.

**The attribute that matters is `linguist-generated`, not `-diff`.** They are read
by different tools on different surfaces: `-diff` suppresses the hunk in a *local*
`git diff`, while `linguist-generated=true` is what collapses a file behind "Load
diff" in a GitHub pull request — the surface review actually happens on. Marking
this file generated to say "read this diff" would have done the exact opposite of
what §26's risk asked for, so the attribute is written out negated rather than
merely omitted: the decision is then recorded in the file, where a later reader can
see it was made.

The stale-file risk that remains is the existing
[deferred.md](../deferred.md) entry, now naming the command.

## One module reads the generated file

`src/api/types.ts` aliases the shapes the app uses — `Account`, `NewAccount`,
`Currency`, `ProblemDocument`, `ProblemType`, `ValidationError` — and nothing else
imports `schema.gen.ts`. Every alias resolves to the generated file, so the
indirection adds no hand-written claim; what it buys is names that read at a call
site instead of `components['schemas']['AccountResponse']`, and one file to look at
when the API moves.

## The demonstration is a `@ts-expect-error`, which asserts in both directions

`src/api/types.test.ts` holds four shapes the backend does not produce — an amount
as a string, a member left out, an unquoted currency, an invented URN — each under
a `@ts-expect-error`.

The directive is doing more than silencing an error. TypeScript reports an *unused*
one, so each is also an assertion that the mistake is still a mistake: if the
backend widened `currency` to a plain string, or dropped a member from the response,
the build would fail on this file naming the case that stopped being wrong. Checked
by removing one directive and watching `tsc` fail with
`Type '"GBP"' is not assignable to type '"EUR" | "USD" | "HUF"'`.

The runtime assertions in those tests are deliberately trivial — the compiler is the
assertion, and Vitest is only what keeps the file in a place someone runs.
