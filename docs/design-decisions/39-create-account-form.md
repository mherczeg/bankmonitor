# Ticket 39 — the first form, and the rule that had to leave the module that owned it

§22 chose the form stack and named the property that shapes every form in this app:
*transforms do not flow through validation*. This is the first form, so that sentence
becomes code — and the code turns out to be one line, in one place, exactly as predicted.

What the build found that §22 could not is smaller and sharper: **registering the schema
under both `onChange` and `onSubmit` prints every message twice**, and **`parseAmount`
refused an opening balance that [ticket 09](09-create-account-endpoint.md) deliberately
allows**. The second is the only thing in this ticket that changed a shipped module.

Touches §16, §18, §22, §23 and §24 of the [initial decisions](00-initial-decisions.md),
and builds on [ticket 33](33-money-format-module.md)'s formatter,
[ticket 34](34-problem-document-module.md)'s message table and
[ticket 38](38-accounts-list-screen.md)'s screen, whose list this form writes into.

---

## `parseAmount` refused an Account that ticket 09 opens on purpose

[Ticket 33](33-money-format-module.md) settled that parsing rejects "negatives and zero",
on the reasoning that parse's domain is "the sums an operator can submit, which is strictly
narrower" than the counts the API can report. [Ticket 09](09-create-account-endpoint.md)
had already settled the other half and the two were never put side by side:

> **A zero opening balance is allowed** — `@PositiveOrZero`, not `@Positive`. An Account
> with nothing in it is a thing an operator may reasonably want.

So zero *is* a sum an operator can submit. The form ticket 39 asks for, built on
`parseAmount` as the ticket directs, would have refused in the browser a request the
backend accepts — a capability the API advertises, hidden behind a client-side rule nobody
chose.

**The rule moved rather than being made optional.** `parseAmount` now refuses a negative
and reads zero as the amount it is; `not-positive` is renamed `negative`, which is what the
check now tests. The question *is zero usable here* belongs to the form asking, because the
two forms answer it differently: this one opens an empty Account, and ticket 40's Transfer
of nothing is not a Transfer.

**Rejected — an opt-in third argument** (`parseAmount(input, currency, { zeroAllowed: true })`).
It keeps the default strict, so a caller who forgets fails safe, and it changes no existing
test. It was rejected because it puts a *form's* rule in the module's signature: the module
would then hold two policies and each caller would pick one, which is the arrangement where
a third form silently picks the wrong one. A parser that reads digits and a form that
decides what an amount has to be are two jobs, and the split is the honest place for them.

**The cost is named, and it lands on [ticket 40](../../.scratch/global-payment-service/issues/40-transfer-form.md).**
Nothing in the frontend now refuses a Transfer of zero before it is sent — the backend does,
with a `422` naming the field, so the failure is correct and merely late. Ticket 40's file
has gained the checkbox, because a rule that moved and was not re-landed is a rule that was
deleted.

The round trip widened as a side effect and is pinned: `formatAmount(0, 'EUR')` is `'0.00'`
and reads back as `0`, so format and parse are now inverses over every non-negative count
rather than every positive one. Ticket 33's record called that the "one place the two are
not inverses"; there is no such place left.

## The Currency is a field, so the schema needs no factory

§22 makes the transfer schema a **factory over the accounts list**, because the decimal rule
there derives from a source Account the form only selects. Here the Currency *is* a field,
so the rule derives from a value the schema already has, and `newAccountSchema` is one
constant rather than `makeAccountSchema(…)`.

What that changes is where the rule sits, not whether it is cross-field. `100.50` is an
amount in EUR and is fillér that do not exist in HUF, so the scale check has to see both
members at once — it lives in an object-level transform and names `openingBalance` in its
issue path, so the form shows it against the input that can be corrected rather than above
the whole form. §22's promise that switching the Currency "immediately re-invalidates"
holds for the same reason it holds there: an object-level validator re-runs on any field
change, so the browser spec changes HUF to EUR and watches the same untouched string stop
being a refusal.

## The transform is the conversion, and the submit handler is where it is asked for

§22, quoting the docs: *"Validation will not provide you with transformed values."*
`onSubmit` receives `z.input` — a Currency and a string — and calls
`newAccountSchema.parse(value)` itself to get `z.output`, which is the generated
`CreateAccountRequest`. The one line is the whole of the workaround.

The property this buys is worth stating plainly: **no React module in this app multiplies by
a hundred.** `money.ts` owns the digits, `accountSchema.ts` owns the rule and the
conversion, and the form owns neither — it does not import `decimalPlacesIn` and would not
know what to do with it. A form that converted inline would work identically and would put
the second copy of the decimal table where nobody looks for it.

`z.infer` aliases the *output* type, which is the confusing part §22 flagged, so
`NewAccountForm` is spelled `z.input<typeof newAccountSchema>` explicitly.

## Registering the schema twice prints every message twice — measured

The obvious wiring is `validators: { onChange: schema, onSubmit: schema }`: validate as they
type, and validate again on a form nobody has touched. It is wrong, and the way it is wrong
is invisible until a field is empty.

Pressing submit on an empty form rendered *"Enter the amount this Account opens with. Enter
the amount this Account opens with."* TanStack's default validation logic runs the **change
and blur validators on submit as well** as the submit one
(`ValidationLogic.js`, `case "submit"`), and each lands under its own key in the field's
`errorMap`. `field.state.meta.errors` is every value in that map, so one rule reported by two
validators is two identical sentences under one input.

`validators: { onChange: newAccountSchema }` — which is exactly what §22 wrote — validates on
every keystroke *and* on a submit from an untouched form. The second registration bought
nothing and cost a duplicated sentence.

**Rejected — de-duplicating the messages at render.** It produces the right screen and leaves
the wrong configuration in place, where the next validator added to the form finds a filter
that quietly hides its output.

## A server refusal is derived from the request, not written into the form

The backend's problem document carries `errors`, an entry per member it refused, and this
form shows each against its own field. Two decisions made that work.

**`validation.ts` is a module of its own, beside `problem.ts` and not inside it.** They read
the same document and answer different questions: `problem.ts` branches on the URN to choose
the heading and the advice, and this one reads `errors` to choose what appears under each
input. Folding the second into the first would grow a second discriminator over one
document, which is the thing §18 made a single URN to prevent — and
[ticket 34](34-problem-document-module.md)'s source-level assertion that `problem.ts` never
says *status* is the precedent for the assertion here: `validation.ts` names neither the URN
nor the response code, enforced by reading its own source. The plausible edit it forbids is
*"only show field detail when the URN is `validation-failed`"*, which would discard detail the
backend chose to send with some other refusal.

**The messages live on the mutation, not in the form's error state.** TanStack Form has an
`onServer` channel and `setErrorMap` to write into it, and it was rejected: an entry there
persists until something clears it, and the only thing that should clear it — the operator
correcting the field — does not, because the framework auto-clears `onSubmit` errors on
change and nothing else. That leaves a screen showing a refusal of an amount that is no
longer in the box.

Deriving them from `opening.error` during render inverts the problem: the messages exist
exactly as long as the failed request does, and a form-level `listeners.onChange` calls
`opening.reset()` the moment anything is edited, which voids the refusal and the *Account N
is open* note together. Both are verdicts on a payload that has just changed. The browser
spec pins the clearing, because it is the half that a screenshot of a correct-looking error
would never show.

**A refused member with no field here is shown, not dropped.** The wire says
`openingBalanceMinorUnits` and the input holds `openingBalance` — ticket 09 put the unit in
the name and ticket 33 keeps the decimal in the field — so something has to walk the two
back together. That map lives in `accountSchema.ts` beside the rules, not in the component:
§24's rule is that logic goes in a plain module and is unit tested there, and "which field
does this refusal belong to" is logic. The component is left with markup and two calls.
Anything the map cannot place, including the contract's `field: null`, is listed under the
heading instead. A message nobody sees is worse than a message in the wrong place, and
silence here would be the frontend deciding an operator does not need to know why.

## The wording is the module's on the way out and the service's on the way in

[Ticket 34](34-problem-document-module.md) established that an operator reads
`problem.ts`'s words rather than the document's `title` and `detail`, which are written for
whoever reads the response. The heading over this form obeys that. The field-level
messages deliberately do not: they are `errors[].message`, verbatim.

The difference is what the two are. A URN names a *situation* this app can have an opinion
about, and there are fourteen of them. A field message names a *constraint*, and rewording it
here would mean holding a copy of every constraint the backend declares — one that drifts the
first time a `@Min` changes. The ticket asks for "the field-level detail the backend's problem
document carries", and passing it through is the only way to carry it.

The client-side copy is this app's own, from `accountSchema.ts`, and it says what the
Currency's rule is rather than that a rule was broken: *"HUF is written without decimal
places, so its amounts are whole numbers."* The example amount in *"Enter an amount in
figures, like 100.50."* is `formatAmount`'s own output, so the shape the form asks for cannot
drift from the shape it accepts.

Where both exist for one field, the client's comes first — it is about what is in the box
now, while the server's describes what was last sent.

## The Currency is named twice on this screen, on purpose

[Ticket 33](33-money-format-module.md) predicted the screens would carry the Currency
themselves and left two shapes open — a column, which
[ticket 38](38-accounts-list-screen.md) spent on the list, and an adornment, which it
assigned to ticket 40. This form has both a `<select>` that chooses the Currency and an
adornment on the amount input showing the one chosen.

That is not the redundancy it looks like. The refusal an operator is most likely to meet here
is *"HUF is written without decimal places"*, and it is only readable if the denomination
they are typing in is in the same glance as the box they are typing in. A select three
inches away, already scrolled past, is not.

Ticket 40's adornment answers a different question — there the Currency is derived from the
source Account and the adornment is the *only* place it appears. Both are the same element
for two reasons.

## The submit button is never disabled for being invalid

It is disabled while a request is in flight, and at no other time. §22 mentions
`canSubmitWhenInvalid`; the reason this form does not lean on the default is that a button
greyed out until every rule passes tells an operator that something is wrong and never which
thing. Pressing it runs the schema over every field at once and puts a sentence under each
one that failed, which is the same click spent on an answer instead of on a shrug.

The browser spec pins the consequence that matters: pressing submit on an empty form asks
for the amount and sends no request.

## `mutations: { retry: false }` is declared for §38's reason

[Ticket 38](38-accounts-list-screen.md) wrote `refetchOnWindowFocus: true` into
`queryClient.ts` though it was already the default, because a property two sections of the
design lean on should not be able to move under a minor version. The mutation default is the
same shape of decision and gets the same treatment.

What it protects is specific. A mutation in this app is a `POST` that opens an Account or
requests a Transfer, and **only the Transfer carries an Idempotency Key** — §3 puts
idempotency around the transaction that moves money, and opening an Account is not one. A
retry policy that silently re-sent this request would open a second Account, and there is
nothing on the wire that could tell the backend not to. `false` is the current default, so
the line changes no behaviour and makes the reason findable.

## The submitting state is rendered and not asserted in a browser

The button reads *Opening…* and is disabled while the request is in flight.
[Ticket 37](37-playwright-harness.md)'s harness answers immediately and offers no way to hold
a request open, so asserting on it would be a race against a local `fulfill` — the same gap
[ticket 38](38-accounts-list-screen.md) named for its spinner, for the same reason, and it is
in [deferred.md](../deferred.md) beside it rather than left unmentioned.

## What this ticket corrected, and what it left standing

**No section of `00-initial-decisions.md` was found false.** §22's transform finding, its
prediction that switching the Currency re-invalidates with no dependency wiring, §23's
element vocabulary and §24's extraction rule all held as written. The only edits to that
file are pointers here.

One earlier ticket record is **corrected**: [ticket 33](33-money-format-module.md)'s
"parsing must reject negatives and zero" and its round-trip caveat both describe behaviour
that no longer exists, and the reasoning is in the first section above.

Two are extended: [ticket 34](34-problem-document-module.md)'s source-level assertion gains a
second module that uses it for a second word, and [ticket 38](38-accounts-list-screen.md)'s
Accounts query gains its first writer — the row this form creates arrives from the endpoint
through an invalidation, never from anything the form knew about the Account it just asked
for.

The harness gained `api.opensAccount` and `api.bodySent`. The second is the one that makes
this ticket's central claim testable at all: a screen that renders `100.50` correctly says
nothing about whether `100.50` or `10050` left the browser, and only the second is what §16's
`long` counts.
