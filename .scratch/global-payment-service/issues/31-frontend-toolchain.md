# 31: Frontend toolchain and the shell of the app

**What to build:** A Vite + React + TypeScript application, running as its **own process**
beside the backend. A meta-framework was rejected on a specific ground: there is no SSR or
SEO requirement, server components actively fight an app whose data is behind a Java API
and whose interesting components are all stateful, and decisively it would add a **second
production runtime** beside Spring.

What lands here:

- **The router**, with the four routes stubbed: accounts, new transfer, one transfer, and
  the transactions list. Chosen for typed route configuration as a deliberate trade against
  ecosystem size. **It needs `strict` on in the TypeScript config** or its inference
  silently degrades — and decide up front whether the generated route tree is committed; a
  half-committed generated file is a confusing diff.
- **The query client**, which will own all server state. **Its retry must be narrowed to
  `5xx` only**, or a `422` gets retried as noise and a `409` races its own `Retry-After`.
- **Bootstrap 5, CSS only, plus CSS Modules.** No component library and no Bootstrap JS
  bundle: this app has no JavaScript-driven components — no dialog, combobox, popover or
  dropdown — so a headless library's entire proposition buys nothing here.
- **The dev proxy to the backend**, which **must not buffer the event stream**, or events
  arrive in a clump when the connection closes and the live UI looks broken in development
  only.

No global state library. Once the query client owns server state and the router owns route
state, there is nothing left to manage; the escalation, if ever needed, is context and a
reducer before a library.

**Blocked by:** None (can start immediately)

**Status:** done

- [x] `npm run dev` serves the app and `npm run build` type-checks it
- [x] Four routes resolve, with placeholder content
- [x] TypeScript `strict` is on and the generated-route-tree decision is recorded
- [x] Query retry is narrowed to `5xx`, with a comment naming the two failures it prevents
- [x] Bootstrap CSS is imported once, with no JS bundle, and CSS Modules work
- [x] The dev proxy passes the event stream through unbuffered
