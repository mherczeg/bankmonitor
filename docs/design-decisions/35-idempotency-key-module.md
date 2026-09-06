# Ticket 35 — what an Idempotency Key identifies, on the client side

§24 named `idempotency.ts` on the extraction list and stated the rule it has to carry:
generate the key once when the form becomes ready, hold it in a ref, reset it only after
a success. The ticket repeats that rule and then adds a sentence that pulls against it —
*"correcting a rejected amount and resubmitting is a new intent, and gets a new key"* —
and building the module is what settled which half survives. **§24's "reset it only after
a success" is struck in place**; the evidence for the correction is the next section.

Touches §5 and §24 of the [initial decisions](00-initial-decisions.md).

---

## An intent is its payload, not the lifetime of a form

The ticket states two rules that pull apart:

> It is **reset only after a success**. […] Correcting a rejected amount and resubmitting
> is a new intent, and gets a new key.

Under a module that resets only on success, correcting a rejected amount does *not* get a
new key. And that path is on the Transfer screen's own acceptance list — *"an insufficient
Available Balance is reported readably"* — so it is the common case, not an edge:

1. The operator submits 500 EUR. The backend claims the key, fails in phase three on the
   Available Balance, marks the record `FAILED` and answers `422`.
2. The operator corrects the amount to 100 EUR and submits again under the held key.
3. [Ticket 17](../../.scratch/global-payment-service/issues/17-duplicate-resolution.md)'s
   resolution table has a row above the `FAILED` one: **a different payload hash is
   `409 urn:problem:idempotency-key-reused`.** The key is now permanently poisoned.
4. `problem.ts` reads that URN correctly and tells the operator the request can *never*
   succeed and to start again from the form — which is a form that will hand them the
   same dead key. Only a remount clears it.

So the module keys the intent on the payload. `keyFor(payload)` returns the held key while
the payload is unchanged and mints a new one when it differs, and `succeeded()` clears the
held key regardless. Both of the ticket's sentences are then true at once, and both of its
checkboxes still hold: a failure alone never resets — only a *changed intent* does, and a
changed intent is by definition not the intent that failed.

**What this buys is structural rather than defensive.** The client's rule is now the same
rule as the server's — the backend stores a hash of the payload beside the key and refuses
a key arriving under a payload it did not first see — so `idempotency-key-reused` is not
handled here, it is made unreachable from this client. The URN keeps its entry in
`problem.ts` because a second tab, a restored session or a hand-written client can still
produce it; nothing this form does can.

**Rejected — `current()` / `reset()`, exactly as the checkboxes read.** The cheapest
module, and it pushes the hard part into the React layer: the form would have to call
`reset()` itself on a payload change, or on seeing the key-reused URN. §24's extraction
rule exists to keep logic out of that layer, and "the component remembers to reset the key
when the right field changes" is precisely the discipline that erodes.

**Rejected — resetting on `urn:problem:idempotency-key-reused` as well as on success.**
Escapes the dead end, but only after the operator has already reached it and read a
message telling them the request can never succeed. It also puts a URN branch inside the
form, where `problem.ts` is supposed to be the only module that reads one.

**Minting is lazy, where §24 says "generate it once when the form becomes ready".**
`startIntent()` opens the supply and mints nothing; the first `keyFor` mints. §24 argues
about *reset* and never about *when*, so this is a deviation from its wording rather than
from its reasoning: a key minted at mount would be minted against a payload nobody has
filled in yet, and the first real read would have to replace it — a reset performed
without anyone asking for one, in the module whose whole argument is that it resets only
when asked. Lazy, "once per intent" is what actually happens; eager, it would be "once per
form, then again".

## What the form owes the module: the payload that attempt sent

Keying on the payload opens one door as it closes another. If the payload handed to
`keyFor` is read live off the form rather than captured at submit, an operator editing the
amount while an attempt is in flight makes the next attempt a *different* intent — a fresh
key, a second Transfer on the wire, and the double send this module exists to prevent,
arriving through the mechanism that prevents the other one.

The module cannot close this itself. It is never told that a submit happened — it cannot
tell a read for rendering from a read for sending — and giving it a third method to say so
would spend exactly the surface that makes "a failure cannot reset the key" a property of
the type (below) rather than a discipline.

So it is a contract on the caller, and
[ticket 40](../../.scratch/global-payment-service/issues/40-transfer-form.md) carries it:
**the payload passed to `keyFor` is the one that attempt sends, captured at submit.** A
TanStack Query mutation satisfies it by default — retries re-invoke `mutationFn` with the
variables `mutate` was called with — so the form gets this right by doing the obvious
thing, and gets it wrong only by deliberately reaching for live form state inside the
request. Named in the frontend README and in `keyFor`'s own doc comment, so the obvious
thing is also the documented one.

**Rejected — capturing the payload inside the module at a `submitting()` call.** It moves
the contract from the caller's code into the module's, which sounds better until the third
method exists: `idempotency.test.ts` asserts the surface is exactly `keyFor` and
`succeeded`, and that assertion is what makes the graded rule unbreakable. Trading it for
a hazard that a mutation library already handles is a bad trade.

## A failure cannot reset the key, because there is no way to report one

The surface is two methods: `keyFor` and `succeeded`. There is deliberately no `failed()`.

The graded rule — *a success resets the key, a failure does not* — is then a property of
the type rather than of the calling code. A form that wanted to reset on failure could not
express it, and a reviewer checking the rule reads the interface instead of auditing every
call site. `idempotency.test.ts` asserts the surface is exactly those two names, so a
third method added later has to argue with a red test first.

This is also why the module is never handed the *response*. It is told one thing — the
intent went through — and everything else it needs it reads off the payload it was
already given.

## Member order is normalised, and the asymmetry says why

Comparing payloads means serialising them, and `JSON.stringify` preserves the order the
members were written in. An intent rebuilt in a different order — a spread, a form library
reconstructing its values — would serialise differently and look like a different intent.

The two ways of being wrong are not equally bad:

| Mistake | Consequence |
|---|---|
| reports *changed* when nothing changed | a fresh key mid-retry → the server sees a second Transfer → **the double charge** |
| reports *unchanged* when something changed | `409 idempotency-key-reused` → a refusal, no money moved |

The first is the exact failure the whole mechanism exists to prevent, so the comparison
has to depend on values and on nothing else. `canonicalise` sorts each record's members by
name, recursively, through `JSON.stringify`'s replacer; arrays are left alone, since their
order *is* a value.

`localeCompare` is not used for the sort — its ordering depends on the runtime's locale
data, which would make the comparison an environment-dependent thing rather than a
value-dependent one.

The table is about the comparison being wrong. A payload edited while an attempt is in
flight lands in the same first row without the comparison being wrong at all: it is a true
change, read at the wrong moment. That one is the caller's to prevent, above.

**A member set to `undefined` and a member left out canonicalise identically**, because
`JSON.stringify` drops both. For a form payload those mean the same thing — the field is
not filled — so it is a correct collapse here rather than a tolerated one. It would stop
being correct for an intent where "absent" and "explicitly nothing" differ, which is worth
knowing before this module is reused for one.

## `crypto.randomUUID`, and the context it needs

Version 4 UUIDs, which is what [ticket 14](../../.scratch/global-payment-service/issues/14-request-transfer-endpoint.md)
accepts and anything else is a `400`. `crypto.randomUUID` is the platform's own, needs no
dependency, and is available unpolyfilled in the browsers this targets and in the Node the
tests run on.

**Rejected — a `Math.random` fallback behind a feature check.** The API is exposed only in
a secure context, and every way this app is meant to run is one. A fallback would mean
shipping a second, weaker UUID source for a misconfiguration, which is a permanent cost
against a temporary mistake. The constraint and what it fails like are in the frontend
README; the deferral is in
[deferred.md](../deferred.md#idempotency-keys-need-a-secure-context-to-be-minted).

## Two duplications that had been waiting for a third instance

[Ticket 34](../../.scratch/global-payment-service/issues/34-problem-document-module.md)'s
comments named two pieces of repetition it declined to extract at two instances and
forecast this ticket would make three; its
[design record](34-problem-document-module.md) carries the second of them. This ticket
made three, and both extractions were taken:

**`isRecord`, in `api/records.ts`.** `problem.ts` narrowed an `unknown` before reading
`type` off it, `retry.ts` did the same before reading `status`, and `canonicalise` needs
the same question asked of every member it walks. One line, two traps in it — `typeof
null` is `'object'`, and so is an array, which must not be walked as though its members
had names worth sorting. The array clause is new: neither earlier caller needed it, and
neither is harmed by it, since an array has no `type` or `status` to read anyway.

**`plainModuleRules`, in `testsupport/plainModule.ts`.** The block that reads a module's
own source through Vite's `?raw` and asserts what it imports and that React is not in it
was written out in `money.test.ts` and again in `problem.test.ts`. It emits its two `it`s
into the enclosing `describe` rather than opening one of its own, so `problem.test.ts` can
keep its third assertion — that the module's source does not contain the word *status* —
alongside them. It is named for the rules rather than for a single `it`, because two is
what it emits.

Both were extractions rather than rewrites: the assertions are the same ones, and the full
suite was green before and after.

**One pin loosened as a consequence, and it is worth naming.** `problem.test.ts` asserted
its subject imports `['./types']` and now asserts `['./records', './types']`, because
`problem.ts` took the extracted narrowing. The mechanism is exactly as exact — an import
nobody expected is still a red test — but what the assertion *guarantees* moved from
"imports only the generated types" to "imports only those and this folder's own narrowing".
§24's rule is about React and about logic reaching into the React layer; `records.ts` is
neither, so the pin still catches what it was written to catch. A future extraction that
loosens it again should have to argue with this paragraph.

## What this ticket did not need the backend for

The endpoint that will carry the key does not exist —
[ticket 14](../../.scratch/global-payment-service/issues/14-request-transfer-endpoint.md)
and [17](../../.scratch/global-payment-service/issues/17-duplicate-resolution.md) are both
still open — and it did not block anything here. The module never touches HTTP, so its
whole coupling to the server is three facts, and all three were already settled on paper
and, in the one case that mattered, already in code:

| Coupling | Fixed by | State |
|---|---|---|
| the key must be a well-formed UUID | ticket 14 — missing or malformed is `400` | written, asserted locally |
| what counts as success versus failure | ticket 17's resolution table | already encoded in `problem.ts`, which is done |
| how the key travels (`X-Idempotency-Key`) | §22 | ticket 40's, not this module's |

The second is the one that could have bitten, and the reason it did not is that
[ticket 34](34-problem-document-module.md) had already turned that table into client-side
advice: `request-in-progress` retryable, `idempotency-key-reused` not. The client-visible
semantics of a key were settled before the endpoint that issues one existed.

What genuinely waits on the backend is *verification* — that the header name matches, and
that a real `409` arrives as the table says. That belongs to ticket 40's browser spec
against a scripted backend, and then to the first run against the real one.
