# Ticket 30 — one event stream, carrying hints

§17 settled this endpoint down to a sentence: one SSE endpoint rather than one per
Transfer, a message carrying only a type and a Transfer ID, and no catch-up of any
kind. Building it had to answer four things that sentence does not: which side of a
transaction the send happens on, what the slice is called given that `outbox` already
owns the word *event*, whether the endpoint belongs in the published OpenAPI document,
and what actually puts an SSE response on the wire.

Touches §17 of the [initial decisions](00-initial-decisions.md), §30's package tree and
public-surface count, and §26 by way of the OpenAPI question. It also leaves ticket 23
— the expiry reaper — one obligation and no design.

---

## The send happens after the commit, and the ArchUnit rule is what made that structural

A hint is a cache invalidation. The browser answers one by refetching, and §17 gives
this stream no replay, no buffer and no `Last-Event-ID`. So a hint that overtook the
commit it describes would send the browser to read the row *as it was*, and nothing
would ever tell it again: a page permanently showing the old status, with the backend
correct, the frontend correct, and nothing in any log to say what happened.

The obvious shape — `VerdictRecording` calls the stream — has that bug, and it does not
compile past the test suite either. `LockedPathTouchesOnlyTheDatabaseTest` names
`VerdictRecording` a lock holder and forbids anything reachable from it touching
`org.springframework.web..`. `SseEmitter` lives there. That rule is not being worked
around here; it is right, and for the reason it was written: a settlement holds row
locks on a Transfer and two Accounts, and writing to an arbitrary number of
possibly-slow browser sockets is the clearest example there is of a lock waiting on
something slower than the database.

So the two halves are joined by the container and by nothing else. `transfers`
publishes an ordinary Spring application event from inside its transaction
(`LifecycleHints`); `stream` listens for one at
`@TransactionalEventListener(AFTER_COMMIT)`. One indirection buys both properties at
once — the ordering is structural rather than a comment, and there is no path from the
locked transaction to a socket.

It is worth naming that this is the **mirror image of the outbox**, which the same
callers also reach for. An Outbox Event is written *inside* the transaction because its
whole claim is that the record and the change commit together. A hint is sent *after*
the transaction because its whole claim is that what it points at is already readable.
Two rules that look contradictory until you say what each is for.

`fallbackExecution = true` is set so that a caller with no transaction open gets the
hint immediately rather than silently getting nothing. The ordering property survives:
with no transaction pending, there is no later truth for the hint to overtake. **Nothing
in the suite pins it**, and that is deliberate rather than an oversight: it was pinned
only by the expiry test announcing outside a transaction, which was the wrong shape for
the reason below. Removing the flag was run against the whole suite and passes. It stays
because a future caller that announces without a transaction should get its hint rather
than silence, which is a claim about callers that do not exist yet and therefore not one
a test can make.

**Tested by** `NoHintOutrunsTheChangeItAnnouncesTest`, which publishes from a
transaction that is abandoned and then from one that commits, and asserts the *first*
message a subscribed browser reads is the committed one. Phrased as a positive
assertion on purpose: the direct form of "this was never sent" is a sleep whose length
is either a slow test or a lie. Swapping the annotation for a plain `@EventListener`
was run, and fails it on the first assertion with the abandoned hint on the wire.

What it does not prove: anything about a transaction that commits and then fails
afterwards — not a state this application can reach — or about two commits racing,
which is §17's explicit non-requirement, since a hint carries no content and therefore
no order.

---

## `stream/`, not `events/`

§30's tree gains a line, and the name was not free. `outbox/` already owns the word
*event* in this codebase, and an Outbox Event is nearly the opposite of what this slice
sends: a durable row addressed to another service, carrying the payload that service
needs, surviving a restart. A message here is addressed to a browser, carries a
Transfer ID, and is gone the moment it is written. Two things called *event* in
adjacent packages would make the asymmetry §17 states deliberately read as an
inconsistency instead.

`CONTEXT.md` already declines "Domain event" as a term for this reason; `stream/` keeps
that line intact.

The slice exports **two public types and no port** — `TransferEvent` and
`TransferEventType`, the value types a hint is made of. The endpoint, the registry and
the send are package-private, so nothing outside the package can reach a subscriber.
The count of public ports in §30 is unchanged at three: there is one implementation and
no seam. This follows ticket 07's and ticket 27's precedent exactly.

---

## Hidden from the OpenAPI document

`EventStreamController.subscribe` carries springdoc's `@Hidden`.

springdoc describes what a handler returns, and what this one returns is an unbounded
`text/event-stream` rather than a body. Left alone it publishes the `SseEmitter`
object's own properties as the response schema — a description of a Spring class, not
of this endpoint. §26 has the frontend generating its types from that document
precisely because nothing in it is written by hand, so an entry that is *wrong* costs
more there than an entry that is *absent*. And the frontend reads this stream with
`EventSource`, not with a generated client.

**Rejected, and it is a real alternative rather than a footnote:** annotating the frame
schema instead — declaring `text/event-stream` with `TransferEvent` as its content and
`@Schema(requiredProperties = …)`, which is the pattern `AccountResponse` and
`AccountSchemaReachesTheDocumentTest` already establish. That would put the three event
names into `schema.gen.ts`, which would let `frontend/src/api/events.ts` pin them with a
`Record<TransferEventType, …>` the way `problem.ts` already pins the problem URNs. A
renamed event would then be a **compile error** rather than a live-update feature that
silently does nothing — and that silent failure is the hazard this ticket's own comments
warn about, recorded as unpinned in
[ticket 36](36-sse-event-module.md) *because* the document said nothing about a stream.

It was not taken here because it is scope beyond the ticket: it costs regenerating
`schema.gen.ts` against a running backend and editing a done module's source and tests.
The consequence of not taking it is stated rather than hidden — the pairing of the three
names across the two halves is enforced today by
`AFinishedTransferTellsEveryBrowserTest` and
`OneStreamCarriesEveryBrowserItsHintsTest` comparing the literal bytes on the wire, on
this repository's side only. `frontend/README.md`'s "the OpenAPI document says nothing
about a stream" stays true.

**Put to the user and declined**, with the alternative above as the thing being declined.
What that leaves is a rename caught on this side and not by the frontend's compiler, and
it is worth being precise about which side is which: the *shape* of the wire is now
pinned harder than it was — `SubscribedBrowser` fails on a named SSE event rather than
skipping it, see above — while the *names* remain paired by two hand-written lists that
no build compares. `events.ts` is deliberately tolerant of an unknown type, so the
failure mode is still a browser that quietly refetches nothing rather than one that
breaks, which is what makes the gap survivable rather than what makes it acceptable.

---

## The default event is pinned in the reader, not in the assertions

The frontend reads this stream with `EventSource`'s `onmessage`, which is dispatched
**only for the default event**. A named SSE event — `.name("TRANSFER_SETTLED")` on the
builder — would therefore leave a deployed `events.ts` deaf. The six assertions comparing
literal bytes did not catch that: `SubscribedBrowser.nextMessage()` matched lines
beginning `data:` and skipped everything else, so an `event:` line was invisible to it in
exactly the way it is *not* invisible to a browser. This was measured rather than
reasoned about — the name was added to `broadcast` and all eight tests stayed green.

So the guarantee is in the reader. `nextMessage()` now fails on any line that is neither
a `data` field, a `:` comment nor the blank line between frames, and every assertion in
the suite inherits it without asking. Seven of the eight tests go red under the same
mutation; the eighth never reads a message, which is right.

An `id` field fails there too, and that is not over-reach: design decision 17 declines
`Last-Event-ID`, so a stream that started sending one would be advertising a catch-up
nothing implements.

The wire literal itself moved to `SubscribedBrowser.frameFor(type, transferId)`, which
had appeared eight times across three classes. It takes the type as a **`String` and not
as `TransferEventType`** — the whole reason these assertions compare bytes is that the
contract is with a deployed `events.ts` this repository's compiler cannot see, and an
expected frame built from the enum would rename itself alongside any rename and go on
agreeing. The type stays a literal at each call site; only the punctuation is shared.

---

## The connection timeout is a bound on a leak, not a policy

`payments.stream.connection-timeout=PT15M`. A reconnect costs one refetch and nothing
else — there is no catch-up to miss, which is the whole point of §17 — so the number is
not trading liveness against anything. What the bound buys is that a browser that
vanished without closing its socket is not registered for ever. Fifteen minutes is long
enough that a watched Transfer settles well inside one connection.

---

## The subscriber count is package-private, and it is the one thing a socket cannot see

"A disconnecting client is cleaned up and does not leak an emitter" is the one item on
the ticket's checklist that cannot be asserted from outside the application. A departed
browser leaves nothing an HTTP client can observe — that is what *departed* means — so
the registry's own count is the only thing that can say whether an emitter is still
being held.

`TransferEventStream.subscriberCount()` exists for that test and is package-private, so
it is not surface. `OneStreamCarriesEveryBrowserItsHintsTest` asserts it as a
**difference rather than a total**: one in-memory application is shared by every test in
the suite, and a total would be a claim about what some other class left behind.

The same test asserts the surviving browser last, and that half is not decoration:
dropping a subscriber must not happen by an exception escaping the broadcast, or one
closed tab would cost every other operator their live updates. Every failure is caught,
not only `IOException` — sending to an emitter the container has already completed
throws `IllegalStateException`.

**The guard has to cover `completeWithError` and not only the send.** The first version
caught everything the write threw and then, from inside that handler, called
`completeWithError` unguarded — which is the call that throws `IllegalStateException`
when the container has already run `AsyncListener.onError` on that async context. So the
same closed tab that the catch was written for could still throw out of `broadcast` and
cost every browser behind it in the loop its hint, one line further along than the
failure being guarded against. It surfaced as an intermittent red in the very test whose
last assertion exists to forbid it. The write and the container's own `onError` are two
ways of discovering one departure and either can get there first; the removal from the
registry is what actually drops the browser, and telling a container that has already
finished is nothing left to do.

---

## Expiry is mapped and covered, and has no producer

The checklist line "settlement, rejection and expiry each emit a message" is **ticked
with a qualification**. `TRANSFER_EXPIRED` is mapped in `LifecycleHints.hintFor` and
reaches a browser — `AFinishedTransferTellsEveryBrowserTest.announcesAnExpiry` calls
`announce` from inside a `TransactionTemplate` that commits, and reads the message off a
real socket. **The transaction is the point of the test rather than scaffolding**: it is
the arrangement ticket 23 is told to use, and announcing with no transaction open would
have delivered too — `fallbackExecution` is on — while exercising the fallback instead,
leaving the only `EXPIRED` payload in the suite travelling a route no caller is meant to
take. But **nothing in this
application expires a Transfer**: ticket 23 is the reaper and is not built, so `EXPIRED`
has no producer.

Inventing one for the test's benefit was declined; a fake reaper asserts that the fake
works. The half that exists is genuinely worth covering, because the mapping is exactly
the piece a rename would break in silence.

So **ticket 23 inherits one obligation and no design**: call
`LifecycleHints.announce(transferId, EXPIRED)` inside the transaction that releases the
reservation. Everything from there is built and covered.

The mapping is an exhaustive `switch` over `TransferStatus` rather than a lookup or a
naming convention, so a fourth status is a compile error here rather than a Transfer
that finishes without any browser being told — a failure that would be invisible from
both ends, with the backend correct, the frontend correct, and the page simply never
updating. `PENDING` maps to nothing, and that is covered without asserting an absence:
in `announcesASettlement` the first Verdict leaves the Transfer pending and is announced
like any other, and the first message the browser reads is nevertheless the settlement.

---

## What the build found

### Spring Framework 7 no longer commits the SSE response before initialising the emitter

**This is the finding that changed the design**, and the tests hung rather than flaked
until it was understood.

Every test here was written against the assumption — true of Spring for years, and
stated in the handoff as the branch's riskiest unverified claim — that
`ResponseBodyEmitterReturnValueHandler` commits and flushes the response before handing
the emitter on, so that response headers reaching a client proves the subscriber is
registered. In Spring Framework 7.0.9 those two lines are gone:

```java
// what 6.x did, and 7.0.9 does not
// Commit the response and wrap to ignore further header changes
outputMessage.getBody();
outputMessage.flush();
```

7.0.9 wraps the response and initialises the emitter, and writes nothing. So an emitter
nothing is sent on leaves the whole response sitting unflushed in Tomcat's buffer: no
status line, no `Content-Type`, nothing on the socket at all. All four tests timed out
inside `HttpClient.send`, waiting on headers that were never coming.

**This is not a test problem.** §17's entire no-catch-up argument is "the client
refetches when the stream opens", and a browser learns the stream opened from
`EventSource`'s `onopen`, which fires on the response headers. Under 7.0.9 with nothing
written on subscription, `onopen` would not fire when a browser subscribed — it would
fire whenever some *unrelated* Transfer next finished. A browser subscribing to a quiet
system would sit with no `onopen`, never do its convergence refetch, and show whatever
it had. The one property that makes a dropped connection free is the property that
breaks.

The fix is one SSE **comment** written on subscription, and a comment is the SSE
specification's own no-op: a line beginning with `:` that `EventSource` discards without
dispatching anything. It costs the browser nothing, cannot be mistaken for a hint, and
is invisible to `SubscribedBrowser` too, which matches on `data:`. What it does is put
the response on the wire.

The claim `SubscribedBrowser` documents is now a property of *this application* rather
than of Spring: the endpoint registers the browser and only then writes the comment that
flushes the response, so a header reaching the client proves the registry already has
it. If that write were ever dropped these tests would hang rather than flake, which is
the failure worth having.

### A socket closing does not, on its own, make the container run the emitter's callbacks

The disconnection test originally ended with a second, quieter assertion: that a browser
closing *cleanly* was dropped without needing a write, on the theory that a closed tab
fires the container's callbacks at once while one that vanishes does not. It failed —
the count stayed at one — and the theory was wrong twice over.

First, the test has only **one way to leave**: `SubscribedBrowser.close()` is the same
method whether it is called by hand or by try-with-resources, so the two halves were
never testing two different departures. Second, and the substantive part: the response
is an open async one that nothing is reading from, so within any interval a test could
name, the server learns the client is gone only by trying to write to it — and even then
the first attempt after the close can still succeed into a kernel buffer. Waiting
without sending was measured here as waiting for something that never happens.

That is why the surviving assertion has the broadcast *inside* the `await`, and why
there is no second one. A departure is a departure.

### The suite's JVM now lingers at shutdown

Surefire reports `Surefire is going to kill self fork JVM. The exit has elapsed 30
seconds after System.exit(0)` on runs that include the stream tests, and not on runs
that exclude them. The build passes; the cost is build time. Emitters left registered by
tests keep async requests open, and the context's shutdown waits on them. Not chased
here — it would mean adding a production method or a Tomcat setting for a test's
convenience — but recorded so the next person to see the line knows what it is.
