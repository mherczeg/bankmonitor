# Ticket 36 — a stream message as a list of cache keys

§17 decided the shape of a message (`{ type, transferId }`, no catch-up) and §24 decided
what the frontend does with one: `invalidationsFor(event): QueryKey[]`, pure, so the SSE
yak-shave disappears. Building it settled three things those sections left open — what the
function is handed, what a query key looks like, and what the event types are actually
called — and **corrected §21's key shape**, which would have made invalidating the
Transactions list refetch every Transfer page the operator had ever opened.

Touches §17, §21 and §24 of the [initial decisions](00-initial-decisions.md).

---

## The function takes the frame, not a parsed message

§24 wrote the signature as `invalidationsFor(event)`, and the ticket describes "a function
over a plain object". The module takes the raw `data` string instead.

The argument for the object is symmetry with [`problem.ts`](34-problem-document-module.md),
which takes an already-parsed `unknown` because `fetch` did the parsing and the parse
failure was somebody else's to catch. A stream has no such somebody. Parsing is the *first*
thing that fails on one — a keep-alive comment, a proxy's error page injected mid-stream, a
frame cut in half by a dropped connection — and the code that would catch it is a message
handler, which is exactly the place this design spent §17 emptying out.

So the split is:

```ts
source.onmessage = (message) => {
  for (const key of invalidationsFor(message.data)) queryClient.invalidateQueries({ queryKey: key })
}
```

No `try`, no `JSON.parse`, no branch. The function is **total**: every frame the wire can
deliver maps to a list of keys, and the ones it cannot read map to none. That turns
"malformed frame" from a runtime hazard into three unit tests over string literals, which is
the same trade §24 made for the event types themselves.

**Rejected — parsing at the call site.** It moves one `try` into ticket 42 and takes the
interesting failures out of reach of a test that needs no stream to run.

## §21's query keys are corrected: a list and a detail are separate branches

§21 sketched the invalidation with the keys inline:

```ts
queryClient.invalidateQueries({ queryKey: ['transfers', e.transferId] })
queryClient.invalidateQueries({ queryKey: ['accounts'] })
```

A query key is a **path**, and invalidating one invalidates everything beneath it. Under
that sketch the Transactions list is `['transfers']` — the prefix of every Transfer page —
so invalidating the list marks every Transfer the operator has opened stale, and the active
ones refetch. Nothing about it looks wrong, and nothing about it fails; it just does more
work than anyone asked for, quietly, forever.

The keys are therefore two branches rather than a parent and its children:

| Cache entry | Key |
|---|---|
| every Account | `['accounts']` |
| every Transfer | `['transfers', 'list']` |
| one Transfer | `['transfers', 'detail', id]` |

**This was measured, not reasoned about.** With the list key changed back to §21's
`['transfers']`, the assertion that a message about Transfer 7 leaves Transfer 8 alone
turns red. That test hands the returned keys to a real `QueryClient` seeded with all four
entries, rather than comparing arrays — comparing arrays would prove the module returns
what the test expects and nothing about what those keys reach.

## The keys live in their own module, before any screen needs them

`queryKeys.ts` exists because two sides have to agree on a name: the screen that files an
answer under it, and this module, which says that answer is stale. Written out at each of
them they can drift, and **a drifted key fails silently** — the page keeps showing what it
loaded and nothing refetches. There is no error to see.

That the first caller is the event module rather than a screen is an accident of ticket
order, not a reason to leave the vocabulary inside `events.ts`: tickets 38, 41 and 43 read
these keys, and a screen importing its cache key from the stream module would have the
dependency backwards.

**A Transfer is named the way the URL names it.** `/transfers/$transferId` hands the page
a string, so the key takes a string and the number arriving in a message is converted to
one. The conversion has to happen somewhere, and this direction is the one that cannot
fail: `String(7)` is `'7'`, while `Number(id)` of a mistyped URL is `NaN`, which keys a
query nothing will ever answer.

## All three terminal events invalidate the same three keys

It reads like a table nobody has finished filling in. It is the design: **every terminal
state releases the source Account's reservation**, so a balance moves whether the Transfer
settled or was refused, and only settlement moves it in the way the operator was hoping
for. A rejection that invalidated only the Transfer would leave the Accounts screen showing
an Available Balance that is short by the amount of a Transfer that will never happen.

The set is closed for the same reason `TransferStatus` is: a Transfer leaves `PENDING`
exactly once and never moves again. There is deliberately no message for a Transfer being
*created* — the only browser that could care already knows, because it is the one that
submitted it.

## The event names are a contract written ahead of the backend

Ticket 30 has not been built. Nothing generates these three strings — the OpenAPI document
describes request and response bodies and says nothing about a stream — so they are written
out twice, here and in the emitter, and this is the record of the pairing:

```
TRANSFER_SETTLED   TRANSFER_REJECTED   TRANSFER_EXPIRED
```

Screaming case because the backend's natural source for them is an enum beside
`TransferStatus`, which Jackson serialises this way by default. They travel in the frame's
JSON body on the **default `message` event**, not as named SSE events.

**Rejected — named SSE events** (`event: TRANSFER_SETTLED`, the ID as the data). Named
events force `addEventListener` per name, which means the browser silently drops any type
this build does not know before this module ever sees it. The behaviour is nearly the same;
what is lost is that the "an unrecognised type yields no keys" rule stops being something a
test can state, and becomes a property of the browser's dispatcher. §17's thin-message
design is worth exactly as much as the test that pins it.

## What the module is not allowed to import, and the type that follows

§24's plain-module rule is asserted mechanically ([ticket 34](34-problem-document-module.md)
built the assertion): the source may name only its listed imports, and may not match
`/react/i` at all. `QueryKey` is a type the query library exports — and importing it would
fail the rule on the package name, `@tanstack/react-query`, even as a type-only import that
erases before anything runs.

The rule is right, and the type is one line, declared in `queryKeys.ts` beside the keys it
describes:

```ts
export type QueryKey = readonly (string | number)[]
```

It is structurally what the library takes, and rather than *declare* that compatibility the
test *asserts* it: the keys are handed to a real `QueryClient`, and the entries it
invalidates are read back. If the two types ever stop agreeing, the ticket-42 call site
stops compiling and this test stops passing — which is a better guarantee than the import
would have bought.

**The rule is transitive and the assertion was not**, which the review of this ticket
caught: `events.ts` passing says nothing about what `queryKeys.ts` imports, and
`queryKeys.ts` is precisely where reaching for the library's own `QueryKey` would be
tempting. Both sources are pinned now.

## What this ticket did not need the backend for

No running backend, no stream endpoint, no `EventSource`, no polyfill, no streaming mock,
and no jsdom — 20 tests, eighteen of them over strings and two building a query cache
in memory. The file runs in about twenty milliseconds. §24
predicted this ("**this makes the SSE yak-shave disappear**") as the payoff of §17's thin
messages; it is the third ticket where the prediction held, after
[33](33-money-format-module.md) and [35](35-idempotency-key-module.md).

What ticket 30 owes this module is the three names above, on the default event, as JSON.
What ticket 42 owes it is the two lines quoted at the top of this file.
