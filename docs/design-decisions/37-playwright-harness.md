# Ticket 37 — a real browser against a network the spec writes

§24 named this seam and both of its mechanisms: `page.route()` rather than a service
worker, and `window.EventSource` replaced through `addInitScript` because
`route.fulfill()` has no streaming body. Building it settled how a spec *holds* those two
things — a table of answers rather than a route per answer, and a fake source the spec
reopens by hand — and turned up one trap that costs an afternoon if it is reasoned about
rather than reproduced.

It also spent the generated types' staleness, which was not this ticket's to spend and is
recorded below with what it cost.

Touches §24 and §26 of the [initial decisions](00-initial-decisions.md), and adds to
[ticket 31](31-frontend-toolchain.md)'s lint overrides and [ticket 34](34-problem-document-module.md)'s
message table.

---

## One handler over a mutable table, not a route per answer

The central move of a live-update spec is that **truth changes mid-test**: the same
endpoint answers differently after an event than before it. §24 wrote that as step 2 —
"`page.route` the transfer endpoint to return the settled version" — which reads as a
second registration over the first.

The harness registers one handler for the whole API and keeps a `Map` of answers behind
it. Scripting the same path twice is a map entry being overwritten, and the next request
sees the new answer.

The rejected shape is not wrong, it is *unanswerable without reading Playwright's source*:
two handlers on one URL raise which one is reached first, and an `unroute` raises when it
has taken effect relative to a request already in flight. Neither question exists for a map
overwrite. This is what makes "truth changes" a one-liner in a spec rather than a paragraph
with an `await` in it.

The same table counts what the page asked for, which is how a spec distinguishes a
re-render that came from a refetch from one that came from a page that was refetching
anyway. Without that count the smoke spec proves nothing: an interval poll would pass it.

## `**/api/**` also matches the app's own modules — reproduced, not reasoned about

The obvious matcher for "every request to the API" is the glob `**/api/**`. It is wrong,
and it fails in a way that points nowhere near itself.

A glob matches `api` **anywhere** in a URL, and this app's own modules live under
`src/api/` — `events.ts`, `problem.ts`, `queryKeys.ts`. Under Vite's dev server the browser
fetches those by path, the glob matches them, and they are answered from the spec's table
instead of by the dev server: as the unscripted `404`, or — if a spec ever scripted that
path — as a JSON body that fails the browser's MIME check for a module script. Either way
the page never boots, and **every spec fails with "element not found"**, a symptom whose
cause is not on screen, not in the failing assertion, and not in the file the assertion is
in.

Swapping the predicate back to the glob turns all three specs red exactly this way. That is
the reason it is written down: the fix —

```ts
const anApiRequest = (url: URL): boolean => url.pathname.startsWith('/api/')
```

— looks like a needlessly long spelling of a glob, and reads as an invitation to simplify.

## A path is spelled the way OpenAPI spells it, and filled at the call site

Every path a spec names is annotated `ApiPath`, a `keyof paths` over the generated
document, so a path the backend renames or drops fails `tsc` rather than leaving a mock
answering a URL nothing calls.

That spelling is the *template* — `/api/transfers/{id}`, not `/api/transfers/7` — so the
type cannot be the map key on its own. An answer filed under the template is an answer no
request ever matches, and the spec silently gets the unscripted `404` instead of the
refusal it wrote. The harness fills the placeholders from a `PathParams` argument at the
call site, and a placeholder the caller left unnamed **stays in the URL** rather than
becoming the string `undefined` — so the omission surfaces as an unscripted path that names
itself, not as a request to `/api/transfers/undefined`.

This only became reachable when the schema was regenerated (below). Before that,
`/api/accounts` was the only path the document published and it carries no placeholder, so
the defect was latent rather than absent.

## An unscripted path answers `404`, and does not reach the network

A path a spec forgot to script gets the same `404` the backend gives an address that names
nothing, with a `detail` naming the method and path that went unanswered.

The alternative is letting it through to the real network, which would make a spec pass or
fail on whether a backend happened to be running on the developer's machine — the precise
property this seam exists to remove.

The route is registered for **every** spec, not only the ones that script an answer: the
`api` fixture is `auto`, the same way and for the same reason the fake source is. Found
reviewing this ticket, where it was not — a spec that never destructured `api` registered
no route at all, and its requests went to the dev server and through its proxy to whatever
backend was running. That is the failure this section rules out, arriving by the one door
the fixture had left open. The smoke suite now carries a spec that scripts nothing and
asserts the unscripted `404`, which fails against a running backend if the fixture ever
stops being automatic.

## The fake source departs from `EventSource` in exactly one place

The fake keeps the *shape* of a real connection, because the app's behaviour is written
against it: a source opens asynchronously after construction rather than in its
constructor, and neither a closed source nor a dropped one delivers — a dropped one goes
back to `CONNECTING` and stays there until it is reopened.

It departs in one place, deliberately. A real `EventSource` reconnects on a timer of its
own; this one reopens when the spec says so. **That is the whole point** — it is what makes
a reconnection test a sequence the spec orders rather than a wait on a clock, and it is the
same reasoning that makes `retries: 0` correct below.

**A dispatch on a source that is not open throws rather than being ignored.** Silently
ignoring it would spend the spec's full timeout on an assertion that could never come true;
throwing names the mistake in the line that made it.

The dropped half of that was a defect, found reviewing this ticket. The guard read `CLOSED`
alone, so a message dispatched between `drop()` and `reopen()` still reached the app. A
spec could drop the connection, dispatch, and assert a live update — and pass against an
app that would show nothing in a browser, which is the one failure a fake exists in order
not to have. It is fixed, and the spec that names it is in the smoke suite.

## StrictMode makes "the app's subscription" briefly ambiguous

React's StrictMode mounts an effect, tears it down and mounts it again. A subscribing
component therefore leaves a **closed source behind a live one**, and "the source the app is
holding" is two objects for a moment.

`eventStream(page)` binds to the newest *open* source, which makes that invisible — but only
once the remount has happened. So it must be asked for **after the page has rendered**, not
before the first assertion. Both smoke specs call it after their first `toHaveText`, and
that ordering is load-bearing rather than stylistic.

## `retries: 0` is load-bearing

The fake event source exists so that every sequence is ordered by the spec rather than by a
timer. A spec that only passes on a second attempt therefore has a race in it, and a retry
would hide exactly the thing the design spent the fake to eliminate.

## One browser, and the dev server rather than a preview of the build

**One browser.** These specs assert what the app does, not what a rendering engine does.
The one thing a second engine would genuinely catch is a CSS or layout difference, which
none of them look at; three engines would triple the run and the browser download for no
assertion that could tell them apart.

**The dev server, not `vite preview` over a build.** Nothing here touches the backend — the
whole network is scripted — so a production bundle would add a build step before every run
and change nothing that any spec can see. It is also what lets the smoke spec's subject page
live outside `src/`, where the production build cannot reach it.

## Vitest and Playwright both claim `*.spec.ts`

Both runners' defaults collect `*.spec.ts`, so `vitest run` swept up the browser specs and
failed the suite with *"Playwright Test did not expect test() to be called here"* — whose
listed causes are all about configuration files and duplicate installs, none of which is
what happened.

The fix is `vitest run --dir src`. It is the smallest one that keeps
[ticket 31](31-frontend-toolchain.md)'s **"no `vitest.config.ts`"** decision intact — that
decision is explicit, and adding a config file to state an exclude would spend it on a
one-line problem. The two runners now divide by directory: `npm test` is `src/`, `npm run
test:browser` is `e2e/`.

## Two oxlint rules are off under `e2e/`, each scoped to where it is wrong

[Ticket 31](31-frontend-toolchain.md) established that `.oxlintrc.json` cannot carry a
reason, so the reasons live here.

**`react/rules-of-hooks`, off under `e2e/harness/` only.** It is a false positive:
Playwright's fixture callback takes a parameter named `use`, and the rule reads that call as
React's `use` hook in a function that is not a component. Renaming the parameter would
silence it without a config change and was rejected — `use` is the name in every Playwright
example, and a harness file is exactly where a reader should recognise the fixture shape.
The override stops at `e2e/harness/` rather than `e2e/`, because the subject page under
`e2e/smoke/` is real React with real hooks and the rule has real work to do there.

**`react/only-export-components`, off under `e2e/`.** The same Fast Refresh rule ticket 31
switched off for `src/routes/`, firing for the opposite reason: a subject page is a dev-server
entry module and exports nothing at all. Fast Refresh is not a property any browser spec
depends on.

## The generated types were regenerated here, and it cost four messages

`src/api/schema.gen.ts` was stale: it published only `/api/accounts` and was missing the
four `ProblemType` URNs the backend emits for a refused Transfer — `self-transfer`,
`unknown-account`, `insufficient-funds`, `cross-currency-unsupported`.

Regenerating is `npm run api-types` against a running backend, and it is not free here:
`problem.ts` keys its table on `Record<ProblemType, ProblemMessage>` and `problem.test.ts`
keys its expectations the same way, so four new URNs are **four compile errors** until
[ticket 34](34-problem-document-module.md)'s work is extended to cover them. That is the
exhaustiveness §26 was designed to produce, working exactly as intended — the cost is real
and so is what it bought.

The four messages were written under ticket 34's rule that **`retryable` answers "would
trying again ever help", asked by a person**. Three are `false`: the same request can never
succeed while it names the same Account twice, an Account that does not exist, or two
Currencies this service cannot yet convert between. `insufficient-funds` is `true`, and is
the interesting one — Available Balance is the balance less what other Transfers have
reserved, and **both figures move on their own**, so the identical Transfer goes through
once a reservation elsewhere releases. `cross-currency-unsupported` names a capability
rather than a rule, which is why its wording says "yet" and why ticket 26 will delete the
entry rather than reword it.

**Why it happened in this ticket rather than in 38 or 41.** The alternative was to build the
harness against a document that does not describe the backend, which contradicts the one
property the harness is for: a fixture typed from the generated types is worthless if the
generated types are wrong. It also would have left `refuses` broken on templated paths in a
way nothing could reach, and so nothing would have found.

The [deferred entry](../deferred.md) on stale generated types is unchanged and still stands:
it is about the absence of an enforcement point, not about this instance of drift.

## The smoke spec's subject is a fixture page, and should not survive ticket 42

`e2e/smoke/subject.tsx` is a page that exists only for this ticket, because **no screen
fetches anything yet** — the harness is 37 and the screens are 38 to 43.

What keeps it from being a test that proves its own scaffolding: its subscription is the
four lines quoted **verbatim from `events.ts`'s own documentation**, the ones
[ticket 42](../../.scratch/global-payment-service/issues/42-live-updates.md) will put on the
Transfer page — that ticket describes the same sequence, against the same fake source, as
its own acceptance criterion. So what the spec proves is that those four lines survive a
real browser, not that a purpose-built test app works.

It cannot reach production — `vite build` bundles the one `index.html` at the project root,
and this page is neither that file nor under `src/`. **It should be deleted when ticket 42
carries the same sequence for real**, and that is the reopening condition rather than a
nice-to-have.

**The specs stay on the Accounts endpoint**, which is the same machinery: a settled Transfer
invalidates `['accounts']` alongside the two Transfer keys, so the event drives a real
refetch of a real key. Extending the subject page to also read one Transfer — and so to
assert `PENDING → SETTLED` on the detail key, the branch carrying the number-to-string
conversion — was considered and deliberately not done: it is more scaffolding on a page that
ticket 42 deletes, and 42 asserts the same sequence on the screen that ships.

The consequence to know about: `api.transfer`, `api.transfers` and the `aTransfer` fixture
**ship without a spec exercising them**. They are not dead weight — each returns or accepts a
whole generated shape, so a member the backend renames fails `tsc` here — but the
placeholder filling they were built alongside is proven by construction and by `tsc`, not by
a passing spec. Ticket 38 is the first to run them.
