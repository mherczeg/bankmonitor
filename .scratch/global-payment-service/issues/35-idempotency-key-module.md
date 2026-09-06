# 35: The Idempotency Key lifecycle

**What to build:** The frontend half of a graded requirement, as a plain module with no
React in it.

An Idempotency Key identifies **what the operator meant to do**, not an individual HTTP
attempt. So:

- It is generated **once**, when the form becomes ready.
- It is **held** across retries — the same key goes out on every attempt of the same intent.
- It is **reset only after a success**. Correcting a rejected amount and resubmitting is a
  new intent, and gets a new key.

**The failure this exists to prevent:** generating the key inside the request function
defeats the entire mechanism — every retry gets a fresh key, the server sees a brand-new
Transfer each time, and the network retry the whole feature exists to make safe becomes the
double-charge it was meant to prevent.

Keys must be well-formed UUIDs; the backend rejects anything else with `400`.

**Blocked by:** 31

**Status:** done

- [x] A key is generated once per intent and returned stably on repeated reads
- [x] Repeated failed attempts of one intent reuse the same key
- [x] A success resets the key; a failure does not
- [x] Keys are well-formed UUIDs
- [x] Unit tests cover the retry-reuse and reset-on-success rules with no React

## Comments

**This ticket's own two sentences pull apart, and the module had to pick one.** *"Reset
only after a success"* and *"correcting a rejected amount and resubmitting is a new
intent, and gets a new key"* cannot both be built. Under the first, correcting a refused
amount resubmits under the held key; ticket 17's resolution table answers a key arriving
under a payload it did not first see with `409 urn:problem:idempotency-key-reused`, which
`problem.ts` correctly reads as *never retry, start again from the form* — a form holding
the same dead key. Only a remount clears it, and the path runs through this screen's own
acceptance list (*"an insufficient Available Balance is reported readably"*), so it is the
common case rather than an edge. The module therefore keys the intent on the payload, and
both sentences come true: a failure alone still never resets, because a changed intent is
by definition not the intent that failed. §24's *"reset it only after a success"* is
struck in place in `00-initial-decisions.md`, the evidence being in decision 35.

**What that buys is structural, not defensive.** The client's rule is now the server's
rule, so `idempotency-key-reused` is unreachable from this form rather than handled by it.
The URN keeps its entry in `problem.ts` because a second tab or a hand-written client can
still produce one.

**The graded rule is a missing method rather than a discipline.** The surface is `keyFor`
and `succeeded`; there is deliberately no `failed()`. A form that wanted to reset on a
failure could not express it, and a reviewer checking the rule reads the interface instead
of auditing call sites. A test pins the surface to exactly those two names, so a third
method has to argue with a red test first — which is also what made the review's one open
question decide itself.

**The review found the door the payload keying opens.** If `keyFor` is handed live form
state rather than the payload that attempt sent, an operator editing the amount mid-flight
makes the next attempt a different intent — a fresh key, a second Transfer on the wire.
The module cannot close it: it is never told a submit happened, and the third method that
would tell it is the surface the paragraph above spends. So it is a contract on the
caller, now written into [ticket 40](40-transfer-form.md) as a bullet and a checkbox: the
payload is captured at submit. A TanStack Query mutation satisfies it by default, since
retries re-invoke `mutationFn` with the variables `mutate` was called with — the form gets
it right by doing the obvious thing and wrong only by deliberately reaching for form state
inside the request.

**Minting is lazy, which is a deviation from "generated once, when the form becomes
ready".** `startIntent()` mints nothing; the first `keyFor` does. Eager minting would
produce a key against a payload nobody has filled in yet, and the first real read would
have to replace it — a reset nobody asked for, in the module whose argument is that it
resets only when asked. Invisible to the form, and named in decision 35 because §24 argues
about *reset* and never about *when*.

**Both extractions ticket 34 forecast came due here.** `isRecord` in `api/records.ts`
(`retry.ts`, `problem.ts` and now `canonicalise` all narrow an `unknown` the same way,
with the same two traps — `typeof null`, and an array) and `plainModuleRules` in
`testsupport/plainModule.ts` (the `?raw` source-pinning block, written out in
`money.test.ts` and again in `problem.test.ts`). Both were moves, not rewrites; the suite
was green either side. One consequence is worth knowing: `problem.test.ts`'s import pin
went from `['./types']` to `['./records', './types']`, so what it guarantees moved from
"imports only the generated types" to "imports only those and this folder's own
narrowing". The mechanism is as exact as it was.

**The two-axis review changed six things and declined two.** The module's file-level TSDoc
had grown into a second copy of the frontend README's section, so it is back to intent
plus a pointer — AGENTS.md gives the paragraph to the README, which survives, and the
one-line-pointer rule to the code. The secure-context reasoning was stated three times
(`deferred.md`, decision 35, the README); the README now carries what it fails like, and
decision 35 keeps only the rejected fallback. `startIntent<TIntent extends object>()` lost
its type parameter — every instantiation was in the test file, and `keyFor(intent: object)`
does the one job the constraint had, which was making a `canonicalise` returning
`undefined` unreachable; ticket 40 can reintroduce it if pinning the payload shape at the
call site turns out to earn it. `itIsAPlainModule` is `plainModuleRules`, because it reads
as one `it` and emits two. Decision 35 was crediting its licence for `isRecord` to decision
34, where the forecast is actually in ticket 34's comments — a reviewer was misled by that
citation into calling the extraction unlicensed. And the §24 and §5 pointers were four and
five lines claiming to *contradict* a sentence they left standing; the sentence is struck
now, so the pointers are pointers again.

Declined: `testsupport/plainModule.ts` imports `vitest` while sitting under
`tsconfig.app.json`'s `include: ["src"]`, which is true of every `*.test.ts` beside it —
the fix would be splitting test material into its own project reference, which is ticket
31's toolchain to change and more than this finding is worth. And *"double charge"* in the
README was read as importing CONTEXT.md's avoided *Payment* vocabulary; the phrase is this
ticket's own.
