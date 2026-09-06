# Frontend

The browser half of the Global Payment Service: four screens over the REST API, plus a
live event stream on the transfer screen.

It is a plain Vite + React single-page app, and it runs as **its own process** beside the
Spring application. There is no meta-framework: nothing here needs server rendering, and
adding one would put a second production runtime next to the JVM. The cost of that choice
is the second terminal below.

## Run it

```bash
nvm use                        # the Node version in ../.nvmrc
npm install
npm run dev                    # http://localhost:5173
```

The backend has to be running too, in another terminal:

```bash
cd .. && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

`/api` and `/internal` are proxied to `http://localhost:8080`, so the browser only ever
sees one origin in development.

**Two stacks at once** — a second copy for a review or a comparison — needs both halves
moved, because the pair is what has to line up:

```bash
cd .. && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments=--server.port=8081

BACKEND_URL=http://localhost:8081 npm run dev -- --port 5174
```

`BACKEND_URL` is read by `vite.config.ts` and by `npm run api-types`; the port is a Vite
flag. `strictPort` stays on deliberately, so a port already taken is an error at startup
rather than a second server silently answering on a port nobody meant. Nothing else needs
changing: the browser still talks only to the dev server, so
`payments.cors.allowed-origins` on the backend is not involved.

| Command | What it does |
|---|---|
| `npm run dev` | Vite dev server with hot reload |
| `npm run build` | regenerates the route tree, type-checks, then bundles to `dist/` |
| `npm test` | Vitest, once, over `src/` |
| `npm run test:browser` | Playwright, headless — starts the dev server itself |
| `npm run lint` | oxlint |
| `npm run routes` | regenerates `src/routeTree.gen.ts` on its own |
| `npm run api-types` | regenerates `src/api/schema.gen.ts` from the running backend |

## How it is put together

```
src/
  main.tsx            mounts the router inside the query client, imports Bootstrap once
  routeTree.gen.ts    generated — see below
  money.ts            the decimal form of an amount, in and out — see below
  api/                the API's types, the query client, its retry rule, how a failure
                      reads to an operator, where Idempotency Keys come from, the
                      names cached answers are filed under, and what a stream
                      message invalidates
  routes/             one file per route; the file tree is the URL tree
```

**Routing is file-based, with typed routes.** A path is not a string the compiler will
accept anything for: `<Link to="/transfers/nwe">` is a build error listing the routes
that do exist. That typing is what `"strict": true` in `tsconfig.app.json` buys, and it
degrades silently without it, so the flag is load-bearing rather than a style preference.

**`src/routeTree.gen.ts` is committed.** A fresh clone type-checks without running a
generator first, and CI needs no extra step. `npm run build` regenerates it before
type-checking, so a stale committed copy cannot pass; `.gitattributes` marks it generated
so it reads as output rather than as something anyone edited by hand.

**The query client owns all server state**, and it is the only thing that does — there is
no Redux, Zustand or Jotai here, because once the query cache holds server state and the
URL holds route state there is nothing left for one to manage.

Its retry rule is narrowed to `5xx` (`src/api/retry.ts`). The default retries anything,
which is wrong twice over: a `422` is the server's considered answer and asking again
unchanged just makes noise, and a `409` carries a `Retry-After` that the client should
obey instead of racing it with its own backoff. A failure with no status — a dropped
connection — is not retried either; the query client's `refetchOnReconnect` is what
covers that case. A query gets three attempts in total, the first one included, after
which the error reaches the screen.

**Styling is Bootstrap 5's CSS and nothing else** — no `react-bootstrap`, no Bootstrap
JavaScript bundle. This app has no JavaScript-driven components: no dialog, no combobox,
no popover, no dropdown menu. Anything beyond Bootstrap's classes goes in a CSS Module
next to the component that uses it (`src/routes/shell.module.css` is the first).

**The dev proxy asks the backend for `Accept-Encoding: identity`.** Not a micro
optimisation — a gzip encoder holds a two-line event until it has enough bytes to emit,
so a compressed event stream arrives as one clump when the connection closes, and only in
development. The line in `vite.config.ts` puts that failure out of reach.

## Amounts, and the only module that knows about the hundred

Every amount on the wire is a whole count of **Minor Units** — cents for EUR and USD,
fillér for HUF, where a forint has none — because the backend holds money as a `long`
count and a decimal fraction of a Minor Unit is not representable in it. The browser
does not get to decide otherwise: `balanceMinorUnits: 10050` means €100.50 on a EUR
Account and 10050 Ft on a HUF one.

`src/money.ts` is the one place that knows which, and so the only place in the frontend
that multiplies or divides by a hundred. Its surface:

| Export | Reading it |
|---|---|
| `formatAmount(minorUnits, currency)` | `10050, 'EUR'` → `'100.50'`; `10050, 'HUF'` → `'10050'` |
| `parseAmount(input, currency)` | `'100.50', 'EUR'` → `{ ok: true, minorUnits: 10050 }`, or `{ ok: false, reason }` |
| `decimalPlacesIn(currency)` | 2 for EUR and USD, 0 for HUF |
| `CURRENCIES` | the three, at runtime — the `Currency` type is generated and compile-time only |
| `ParsedAmount`, `AmountRejection` | the parse result, and the four names a refusal can carry |

**The two functions are exact inverses over every amount that is valid to submit,
and that is a requirement rather than a coincidence.** A formatted amount is not only
read: it goes into a form field an operator then edits, on the create-Account and
transfer screens both. So the output
carries no currency symbol, no thousands separator and no locale — just the digits and,
where the Currency has decimals, a full stop. Anything else would have to be stripped
back off before the string could be parsed, and the separator that would need stripping
is a comma in one locale and a full stop in another. The screens name the Currency
themselves, beside the amount.

Neither direction does arithmetic on the value. Both work on the digits of the string,
because `Number('100.50') * 100` is `10049.999999999998` and the rounding step that
fixes it is a rounding step in the module whose contract is that it does not round.

**A refusal says which of four things is wrong** — `not-a-number`, `too-many-decimals`,
`not-positive`, `too-large` — so a form can tell an operator what to fix rather than
that something is wrong. Scientific notation, a comma decimal separator and a thousands
separator are all refused rather than guessed at, and so are zero and negatives —
which is the one asymmetry between the two functions: `formatAmount` renders a zero
balance and would render a negative difference, and neither is a sum anyone can send,
so neither reads back. `too-large` is the one refusal that is not
about the operator: a JavaScript number counts exactly only to 2^53 − 1 where the
backend's `long` goes far further, and an amount past that bound would arrive at the
server as a different number than the one submitted.

Adding a Currency is therefore a one-line change in this file, and the compiler finds
it: the decimals table is keyed by the generated `Currency` type, so a fourth Currency
appearing in `schema.gen.ts` fails the build until it has decimals here.

## What an operator is told when a request fails

Every failure of the API arrives as one shape — an RFC 9457 problem document — carrying
a `type` URN that names what went wrong. **That URN is the only member of it this app
branches on.** The backend made it the sole discriminator so that no client has to
reconcile two fields that can disagree, and the two `409`s are the case that makes the
rule worth having: `urn:problem:request-in-progress` is worth retrying and
`urn:problem:idempotency-key-reused` never is, and their status codes are identical.

`src/api/problem.ts` is where that vocabulary becomes something readable:

```ts
problemToMessage(failure): { title, body, retryable }
```

| Export | Reading it |
|---|---|
| `problemToMessage(failure)` | anything a failed request produced → the three fields a screen renders |
| `UNRECOGNISED_PROBLEM` | what a failure this app has no wording for degrades to |
| `PROBLEM_TYPES` | the URNs, at runtime — the `ProblemType` type is generated and compile-time only |
| `ProblemMessage` | the returned shape |

**`retryable` answers "would trying again ever help", which is a question for the person
reading the screen.** It is not the same question `src/api/retry.ts` answers — whether
the query client should silently re-send now — and the two deliberately disagree on the
in-progress `409`: the client must not race the `Retry-After` with a backoff timer of
its own, and the operator asking whether to try again in a minute is owed a yes.

**The wording is written in that module rather than taken from the document.** A problem
document's `title` is the status reason phrase (`"Conflict"`) and its `detail` is a
sentence for whoever is reading the response (`"Invalid request content."`); neither is
advice an operator can act on. The one server-supplied member the screens do use is
`errors`, and it goes against the fields it names rather than into a paragraph.

**The argument is `unknown`, on purpose.** A parsed response body is not a
`ProblemDocument` until something has checked, and typing the parameter would push a
cast to every call site — the hand-written trust the generated types exist to delete.
So a gateway's HTML page, a dropped connection and a URN from a backend newer than this
build all land on the same readable fallback, which advises retrying: the Idempotency
Key is held across attempts, so the cost of being wrong that way is one click.

Adding a URN on the backend is a compile error here, not a silently unhandled case: the
message table is a `Record<ProblemType, …>` over the generated union. Ticket 34's design
record has the argument, including why the module's own source may not contain the word
*status* — and why a test enforces that.

**The four ways a requested Transfer is refused** all arrive as `422`, and three of them
tell the operator that trying again cannot help: both sides naming the same Account, an
Account that does not exist, and two Accounts in Currencies this service cannot yet convert
between. `urn:problem:insufficient-funds` is the exception and advises retrying — Available
Balance is the balance less what other Transfers have reserved, and both figures move on
their own, so the identical Transfer goes through once a reservation elsewhere releases.

## The Idempotency Key, and what it identifies

A key identifies **what the operator meant to do**, not an HTTP attempt at doing it.
Every attempt at one intent — the first, and each retry after a timeout, a `502` or an
in-progress `409` — goes out under the same key, which is the whole reason the backend
can tell a retry apart from a second Transfer. `src/api/idempotency.ts` is the only place
one is minted:

```ts
const keys = startIntent()   // once, when the form becomes ready

keys.keyFor(payload)   // the key this intent submits under, stable while the payload is
keys.succeeded()       // the intent went through; the next read starts a new one
```

Opening the supply mints nothing; the first `keyFor` does. A form that renders the key
before anyone has submitted anything therefore mints it at that read, and every read
after it hands back the same one.

**Minting a key inside the request function defeats the entire mechanism.** Every retry
would arrive under a fresh key, the server would see a brand-new Transfer each time, and
the network retry the feature exists to make safe becomes the double charge it was meant
to prevent. That is why the key is held outside the request rather than produced by it.

**An intent is its payload.** Reading the key back with an unchanged payload hands over
the held one; reading it with a different payload mints a new one, because correcting a
refused amount is a different thing to have meant. This mirrors the backend, which stores
a hash of the payload beside the key and answers `urn:problem:idempotency-key-reused` to a
key arriving under a payload it did not first see. Without the payload half, an operator
whose Transfer was refused against its Available Balance would correct the amount,
resubmit under the key already spent on the old one, and hit a refusal that can never
clear.

**The payload handed to `keyFor` has to be the one that attempt sends** — captured when
the operator submitted, not read live off the form. A retry that re-read a form edited
since it went out would see a changed intent, mint a fresh key, and put a second Transfer
on the wire while the first one is still in flight. A TanStack Query mutation gets this
right by default: it re-invokes `mutationFn` with the variables `mutate` was called with,
so the payload a retry sends is the payload the first attempt sent.

**There is no method for reporting a failure**, and that is the design rather than an
omission: a module that cannot be told about a failure cannot reset on one. Only a success
and a changed intent start a new key.

Keys are version 4 UUIDs, which is all the backend accepts — anything else is a `400`.
`crypto.randomUUID` mints them, so the app needs a **secure context**: HTTPS, or the
`localhost` the dev server serves from. There is no fallback, deliberately — served over
plain HTTP from anything else, a LAN address during a demo being the realistic case,
`crypto.randomUUID` is `undefined` and submitting a Transfer throws. Ticket 35's design
record has the rest.

## The event stream, and the only thing the app does with one

A message off the stream carries an event type and a Transfer ID, and nothing else. It is
a **hint that something changed**, not the change itself — the REST endpoint is where the
new state is read from. So the entire frontend handling of the stream is one pure
function, `src/api/events.ts`:

```ts
source.onmessage = (message) => {
  for (const key of invalidationsFor(message.data)) queryClient.invalidateQueries({ queryKey: key })
}
```

There is no merging, no reconciling a message against cached state, and no ordering logic,
because a hint has no content to merge. A Transfer's status only ever moves one way, so a
refetch overtaken by a later invalidation simply happens again.

**The function takes the frame, not a parsed object.** Parsing is the first thing that
fails on a stream — a keep-alive comment, a proxy's error page, a frame cut in half by a
dropped connection — and a message handler is the worst place to catch that. Owning the
parse makes the function total: anything the wire can deliver maps to a list of keys, and
anything unreadable maps to none. An event type this build does not know yields no keys
rather than throwing, which is what lets the backend add one without breaking a deployed
frontend.

The three types are the contract the stream endpoint has to meet, and nothing generates
them — the OpenAPI document says nothing about a stream, so they are written out here and
in the emitter:

```
TRANSFER_SETTLED   TRANSFER_REJECTED   TRANSFER_EXPIRED
```

Each of them invalidates the same three keys: that Transfer, the Transactions list and the
Accounts list. That is not an unfinished table — **every terminal state releases the source
Account's reservation**, so an Available Balance moves whether the Transfer settled or was
refused, and only settlement moves it the way the operator hoped.

## Query keys, and the one that would have invalidated everything

`src/api/queryKeys.ts` names every cached answer, in one place because two sides have to
agree: the screen that files an answer under a key, and the event module that says it is
stale. A key written out at both can drift, and **a drifted key fails silently** — the page
keeps showing what it loaded and nothing refetches.

| Cache entry | Key |
|---|---|
| every Account | `['accounts']` |
| every Transfer | `['transfers', 'list']` |
| one Transfer | `['transfers', 'detail', id]` |

A key is a **path**, and invalidating one invalidates everything beneath it. The list and
the detail are therefore separate branches rather than a parent and its children: under the
obvious `['transfers']` and `['transfers', id]`, invalidating the Transactions list would
mark every Transfer page the operator had opened stale and refetch the active ones. It
looks correct and it never fails — it just does that forever.

A Transfer is named in a key the way the URL names it, as a string, so the page's key and
an event's key are one key. The conversion happens in `events.ts`, in the direction that
cannot fail: `String(7)` is `'7'`, where `Number(id)` of a mistyped URL is `NaN`.

## The API types, and when to regenerate them

**Nothing here describes the API by hand.** `src/api/schema.gen.ts` is generated from the
OpenAPI document the backend serves, and `src/api/types.ts` is the only module that reads
it — everything else imports `Account`, `ProblemDocument`, `ProblemType` and the rest from
there, under names that read at a call site. Typed that way, a mock the backend would never
send stops compiling, which is what makes the browser tests worth running; `src/api/types.test.ts`
pins four such shapes under `@ts-expect-error`. The [root README](../README.md#the-frontends-types-are-generated-not-written)
has the argument, and what had to change on the backend before the document was worth
generating from.

**Regenerate whenever the backend's API moves**, which means: a new or renamed endpoint, a
changed request or response shape, a new problem-type URN. With the backend running:

```bash
npm run api-types      # reads http://localhost:8080/v3/api-docs
```

Then read the diff rather than committing past it. A change in that file is the backend's
contract moving underneath four screens, and the type errors that follow are the list of
places that have to move with it. `.gitattributes` deliberately does *not* mark the file
`linguist-generated`, so GitHub leaves its diff open in a pull request instead of collapsing
it.

Nothing enforces the regeneration — the file is committed and refreshed by hand, so it can
go stale, and the residual risk is written up in `../docs/deferred.md`.

## The browser tests, and the network they run against

Two test entries, divided by directory. `npm test` is Vitest over `src/` — the pure
modules above, in Node, no DOM. `npm run test:browser` is Playwright over `e2e/` — a real
browser, real focus and blur, real navigation, against a network the spec writes.

Both runners collect `*.spec.ts` by default, which is why `npm test` is scoped
`vitest run --dir src`: unscoped, Vitest picks up the browser specs and fails on
`@playwright/test`.

```bash
npm run test:browser              # headless chromium, starts the dev server itself
npx playwright test --headed      # watch it happen
npx playwright test --ui          # pick specs, step through, read the trace
```

The dev server starts and stops with the run, and an already-running one on `5173` is
reused. **Nothing reaches the backend** — every request the app makes to `/api/` is
answered by the harness, in every spec whether or not that spec scripts one — so these pass
with no backend, no database and no seed data. There
is one browser, because these specs assert what the app does rather than what a rendering
engine renders, and **no retries**, because every sequence is ordered by the spec rather
than by a timer and a spec that only passes on the second attempt has a race in it.

### Scripting the API

Specs import from `e2e/harness/test`, which is Playwright's `test` with the two
substitutions every spec here wants already made: the `api` fixture, and a fake event
source installed before app code runs. Both are in place for **every** spec, named or not —
a spec that destructures neither still runs against the scripted network rather than the
dev server's proxy.

```ts
import { anAccount, aProblem, eventStream, expect, test } from '../harness/test'

test('the balance comes from the API', async ({ api, page }) => {
  api.accounts([anAccount({ id: 1, balanceMinorUnits: 100_50 })])

  await page.goto('/accounts')

  await expect(page.getByTestId('balance-1')).toHaveText('100.50')
})
```

| Call | What it scripts |
|---|---|
| `api.accounts(accounts)` | what `GET /api/accounts` answers with, from the next request on |
| `api.transfers(transfers)` | what `GET /api/transfers` answers with |
| `api.transfer(transfer)` | what `GET /api/transfers/{id}` answers with, at the ID the Transfer carries |
| `api.refuses(method, path, problem, params?)` | a refusal instead of a success; the document carries its own status |
| `api.timesAsked(method, path, params?)` | how many times the browser has asked, since the spec began |

**Calling one of these again replaces the answer** rather than adding a second route, which
is how a spec makes truth change mid-test. A path a spec never scripts answers `404` with a
`detail` naming the method and path that went unanswered — it does not fall through to a
real backend, so no spec can pass because a server happened to be running.

Paths are spelled the way the OpenAPI document spells them — `/api/transfers/{id}`, not a
URL with an ID already in it — and are typed `ApiPath`, so a path the backend renames or
drops fails `tsc`. Placeholders come from the `params` argument. Fixtures (`anAccount`,
`aTransfer`, `aProblem`) each return a whole generated shape for the same reason: a member
the backend adds, drops or renames is a build failure here rather than a spec that passes
against a shape nobody sends.

### Driving the event stream

`route.fulfill()` takes a complete string or buffer — there is no streaming body — so a
mock cannot push an event after the page has rendered, which is exactly what a live-update
assertion needs. So `window.EventSource` is replaced, before app code runs, by a fake the
spec drives by hand.

```ts
const stream = await eventStream(page)      // after the page has rendered

await stream.message(JSON.stringify({ type: 'TRANSFER_SETTLED', transferId: 7 }))
await stream.drop()                         // an error, then connecting
await stream.reopen()                       // a real one does this on its own timer
await stream.state()                        // 'connecting' | 'open' | 'closed'
```

A whole live-update spec is then four ordered steps with nothing waiting on a clock: assert
the page reads pending, re-script the endpoint so **truth changes**, dispatch a message,
assert the page reads settled.

**Ask for `eventStream(page)` after the page has rendered**, not before the first
assertion. React's StrictMode mounts an effect, tears it down and mounts it again, so a
subscribing component briefly leaves a closed source behind a live one; the helper binds to
the newest *open* source, which is only reliable once that remount has happened. A dispatch
on a source that is not open — closed by the app, or dropped and not yet reopened — throws
and names the URL, rather than being ignored and spending the spec's timeout. It is refused
rather than delivered because a real connection in either state delivers nothing, and a
spec that asserted a live update over a dropped one would be passing on an app a browser
would leave stale.

`e2e/smoke/` holds the one spec that proves the harness itself, against a fixture page that
exists only because no screen fetches anything yet. It goes when the Transfer page carries
the same sequence for real. `docs/design-decisions/37-playwright-harness.md` has the
reasoning and the alternatives that were rejected — including the glob that looks right and
breaks every spec at once.
