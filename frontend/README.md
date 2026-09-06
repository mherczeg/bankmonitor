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

| Command | What it does |
|---|---|
| `npm run dev` | Vite dev server with hot reload |
| `npm run build` | regenerates the route tree, type-checks, then bundles to `dist/` |
| `npm test` | Vitest, once |
| `npm run lint` | oxlint |
| `npm run routes` | regenerates `src/routeTree.gen.ts` on its own |
| `npm run api-types` | regenerates `src/api/schema.gen.ts` from the running backend |

## How it is put together

```
src/
  main.tsx            mounts the router inside the query client, imports Bootstrap once
  routeTree.gen.ts    generated — see below
  api/                the API's types, the query client and its retry rule
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
