# Ticket 40 — the screen the Idempotency Key was built for

§22 predicted this form in more detail than any other: a schema that is a **factory over
the accounts list**, a Currency that is an adornment rather than a field, and a
cross-field self-Transfer rule caught before the backend's `422`. All three held as
written, down to the property that made them worth predicting — switching the source
Account re-judges an amount nobody retyped, with no dependency wiring anywhere.

What the build found is about the two things §22 does not mention. **`startIntent()` once
when the form becomes ready** is a sentence about a lifetime, and the only honest way to
write it is structural: the form takes the loaded Accounts as a prop, so it cannot exist
before the list does. And **the Idempotency Key is the one thing on this screen that
nothing renders** — the rules about it are facts about the wire, which is what the browser
harness gained a headers accessor for.

Touches §5, §7, §16, §18, §21, §22, §23 and §24 of the
[initial decisions](00-initial-decisions.md), and builds on
[ticket 35](35-idempotency-key-module.md)'s key module,
[ticket 34](34-problem-document-module.md)'s message table,
[ticket 33](33-money-format-module.md)'s parser and
[ticket 39](39-create-account-form.md)'s form, whose mechanism this one reuses and whose
copy it deliberately does not.

---

## The schema is a function of the accounts list

`transferSchemaFor(accounts)` is §22's `makeTransferSchema(accounts)` under a name that
reads at the call site. The reason for the factory is the sentence the ticket opens with:
a Transfer is denominated by the Account the money *leaves*, and nothing on the form says
which Currency that is. The source Account does, and the source Account is a value the
schema has to be built over rather than a field it can read a rule out of.

So the amount check is cross-field for a second time in this app, and lands in the same
place ticket 39 put it — an object-level transform that names `amount` in its issue path,
so the sentence appears under the input the operator can fix. §22's promise that switching
the source "immediately re-invalidates" `100.50` holds for the reason it held there: an
object-level validator re-runs on any field change. The browser spec moves the source from
the EUR Account to the HUF one and watches an untouched string stop being an amount, then
moves it back and watches it start again.

The adornment on the amount input is the **only** place a Currency appears on this screen.
Ticket 39 has two — a `<select>` and an adornment — and named the difference in advance:
there the adornment repeats a choice, here it reports a derivation.

**There is no Currency input, and there is no client-side cross-currency rule.** The first
is §22's, and deletes a bug class: a payload naming EUR against a HUF Account cannot be
expressed, so nothing downstream has to decide which of the two to believe. The second is
deliberate in the other direction — [ticket 26](../../.scratch/global-payment-service/issues/26-cross-currency-transfers.md)
makes a cross-currency Transfer legal, and a rule written here would be a rule to delete
then. `problem.ts` already has readable wording for the refusal in the meantime, and it
says "yet" for exactly this reason.

## Zero landed here, as ticket 39 said it would

[Ticket 39](39-create-account-form.md) moved the zero rule out of `parseAmount` — an
Account may be opened with nothing in it — and named the cost: *"Nothing in the frontend
now refuses a Transfer of zero before it is sent."* This form is where it comes back, as
one line in `amountToTransfer` and the sentence *"A Transfer has to move more than
nothing."*

It is worth stating why the rule is better here than where it was. `parseAmount` reads
digits; a form decides what an amount has to *be*, and the two forms in this app answer
that differently over the same parser. An opt-in flag on the parser would have put both
policies in one signature and left each caller to pick — the arrangement in which a third
form picks the wrong one silently.

The negative case is where the two forms' answers diverge visibly. An Account cannot open
owing money; a Transfer of a negative amount is a Transfer the other way round, so this
form says so: *"Swap the two Accounts to send it the other way."*

## The self-Transfer refusal is shown against the destination

It is a cross-field rule, so §22 puts it in the form-level validator, and that much was
settled. What the validator still has to choose is **which field the sentence lands
under**, because an issue path with two entries would print it twice.

The destination carries it. The money is already leaving the Account the operator chose
first, and the destination is the side they will change. Attaching it to the source would
be telling them to undo the decision they had made rather than the one they had not.

## The form is mounted only after the Accounts arrive, and that is what makes `startIntent()` once true

The route owns the `queryKeys.accounts()` query and its three states; `-newTransferForm.tsx`
takes `accounts` as a prop. The obvious alternative — one component that queries and
renders both — would work, and would make `const keys = useRef(startIntent())` a claim
resting on React's ref semantics across a loading state rather than on the component's
lifetime.

Splitting it makes the ticket's sentence structurally true instead: the form cannot mount
before the list has arrived, so the supply is opened once per screen. It also gives the
schema factory a non-optional argument, which is the shape it should have — a
`transferSchemaFor(accounts ?? [])` over a list that has not loaded would be a schema
refusing every Account as unknown.

**Fewer than two Accounts is its own state, not an empty form.** Every field would be a
choice with nothing to choose, and the only rule the form could report is one the operator
cannot satisfy on this screen. It says so and points at the Accounts screen.

## The key is derived from the mutation's variables, which is the whole of the retry rule

```ts
mutationFn: (transfer) => requestTransfer(transfer, keys.current.keyFor(transfer))
```

The ticket's sharpest checkbox is that *the payload handed to `keyFor` is the submitted
one* — never live form state read inside the request. The failure it prevents is specific:
a retry that re-read a form edited since the first attempt went out would see a changed
intent, mint a fresh key, and put a second Transfer on the wire while the first is still in
flight.

A TanStack Query mutation gets this right by construction, because it re-invokes
`mutationFn` with the variables `mutate` was called with. Writing the key against the
`transfer` parameter rather than against anything in scope is what takes that from a
default to a property of this file: there is no form state in `mutationFn` to read.

The retry button re-calls `mutate(requesting.variables)` for the same reason. The same
payload reads back the held key, which is the browser-observable half of §5's duplicate
resolution — the backend can only tell a retry from a second Transfer because the key
repeats.

**Ticket 35's module cannot enforce any of this**, and says so: it is never told a submit
happened. This screen is the enforcement, and the browser spec is where it is checked.

## `succeeded()` is called into a form that is already unmounting

`onSuccess` calls `keys.current.succeeded()` and then navigates to
`/transfers/$transferId`, which unmounts this form and the ref with it. The call therefore
changes nothing observable, in this screen as it stands.

It is there anyway. The module's contract is that a success ends an intent, and a caller
that skipped the call would be relying on the unmount to say so — a dependency on routing
that nothing in either module records. It costs one line, and the line that would replace
it is a comment explaining why the contract is not honoured here.

## `reset()` on any non-idle mutation loses a refusal that is still on its way

[Ticket 39](39-create-account-form.md) voids a stale verdict with a form-level listener:

```ts
onChange: () => { if (!opening.isIdle) opening.reset() }
```

Copied here, it is wrong in a window that form has too. `!isIdle` includes **pending**, and
`reset()` detaches the observer from the running mutation. A keystroke while the `POST` is
open therefore leaves the request in flight with nothing watching it: the refusal lands on
a detached observer, `isError` never becomes true, and the operator sees no alert at all
for a request that did go out. Success survives — `onSuccess` is the mutation's own option,
not the observer's — which is why the bug is invisible on the happy path.

The guard is the comment's own words made exact: a *verdict* is what a change voids, and a
request in flight is not one yet.

```ts
onChange: () => { if (requesting.isError || requesting.isSuccess) requesting.reset() }
```

**Ticket 39's form is corrected in place**, on the precedent that ticket set with
`parseAmount`: a defect found in a shipped module is fixed where it is, not worked around
in the ticket that met it. Nothing about either form's behaviour changes outside the
in-flight window, and every existing spec stays green.

**It has no browser coverage**, and cannot: the harness answers immediately, so there is no
in-flight moment to type into. That is the same gap [deferred.md](../deferred.md) records
for the spinner and the disabled button, and this is the first thing behind it that is a
bug rather than an unproven rendering — noted there, because it raises what the held-answer
mechanism is worth.

## `useRef(startIntent())` opens a supply per render and keeps the first

The argument to `useRef` is evaluated on every render and all but the first result is
discarded. It is harmless here — ticket 35 mints nothing until a key is read — but the
claim this screen makes is *"opened once"*, and the obvious spelling does not say that.

```ts
const held = useRef<IdempotencyKeys | null>(null)
const keys = (held.current ??= startIntent())
```

React's lazy-ref idiom, and the smallest edit that makes the sentence in this record true
of the call and not only of the value.

## Two extractions, and what decided each

**`formRefusal.ts`.** `serverRefusalIn` and `messagesUnder` were ticket 39's, written
inside `accountSchema.ts`. Both are mechanism — *which field of this form did the service
name*, and *what appears under one input* — and neither knows anything about Accounts.
This form is their second caller, so they moved out and gained a parameter: the
member → field map each form supplies. `accountSchema.ts` and `transferSchema.ts` each
export a `serverRefusalIn(failure)` bound to their own map, so no call site changed.

**`testsupport/fetchAnswers.ts`.** `api/transfers.test.ts` needed the same `fetch` stub
`api/accounts.test.ts` had written inline, on the precedent
[ticket 35](35-idempotency-key-module.md) set with `plainModuleRules`: a second copy of
test apparatus is the moment it becomes a module. It moved with its state behind two
accessors — `requestedPath()` and `requestSent()` — because a module cannot export a
`let` that its callers can see change.

## The copy is duplicated on purpose, and the mechanism is not

Three of the four amount sentences in `transferSchema.ts` match `accountSchema.ts` word
for word, and they are written out again rather than shared. That is
[ticket 33](33-money-format-module.md)'s rule held to: `money.ts` owns the rule and each
form owns its copy. The two forms already differ on the fourth sentence, and the pressure
to share the three would have to be resisted the first time one of them needs its own
wording — which is now.

The extraction above is the same rule read the other way. `formRefusal.ts` holds no
wording at all.

## Rejected — rendering the Available Balance out of the refusal

`TransferController` puts `availableBalanceMinorUnits` and `currency` on the
`insufficient-funds` problem document, and this screen could read them: *"the Available
Balance is 40.00 EUR"* is better advice than *"does not cover this amount"*.

It was rejected. It makes a second reader of the problem document that branches on the
URN — `problem.ts` would hand back a message, and something beside it would have to decide
that *this* URN also carries two extra members worth rendering. §18 made the URN the sole
discriminator precisely to stop a document growing a second one, and
[ticket 34](34-problem-document-module.md) asserts at source level that `problem.ts` never
reads a status for the same reason. A per-URN extra-members reader would be that second
discriminator wearing a different hat.

The information is not lost, only late: the Accounts screen shows every Available Balance,
the source `<select>` on this form shows each Account's beside its identifier, and both
come from the endpoint that owns the figure rather than from a refusal.

## No invalidation on success, because there is nothing left to invalidate

Ticket 39's form invalidates `queryKeys.accounts()`, because the row it created has to
appear in a list on the same screen. This form navigates away, and
[ticket 38](38-accounts-list-screen.md) settled that **there is no `staleTime`** — so every
screen refetches on mount. An invalidation here would be a call whose only effect is to
refetch a list that is being unmounted.

`queryKeys.transfers()` is the same answer for a different reason: the Transactions list is
not built yet, and `events.ts` already invalidates it on every terminal Transfer event.

## The harness gained `requestsTransfer` and `headersSent`

`requestsTransfer` is `opensAccount`'s shape for the other `POST` — a `201` with the
resource the service made. The Transfer's own page is scripted separately, on the same
reasoning: the screen the form navigates to fetches for itself, and answering both from one
call would hide the fetch.

`headersSent` is the one that makes this ticket testable. Ticket 39 needed `bodySent`
because *a screen rendering `100.50` correctly says nothing about whether `10050` left the
browser*; here the same is true one layer up. **Nothing on this screen renders the
Idempotency Key**, so a form that minted a fresh one per attempt would look, in every
screenshot and every DOM assertion, exactly like one that did not. Playwright lowercases
header names, so the spec reads `['x-idempotency-key']` — noted here because the obvious
spelling silently reads `undefined`, and an assertion that two `undefined`s are equal
passes.

## What this ticket corrected, and what it left standing

**No section of `00-initial-decisions.md` was found false.** §22's factory, its adornment,
its form-level self-Transfer rule and its "no Currency field" all held; §21's mutation
semantics are what the key rule rests on; §23's `<select>`, `.is-invalid` and
`.input-group` vocabulary needed nothing new; §24's extract-and-unit-test rule produced
`transferSchema.ts` and its 21 tests before any of this markup existed.

One earlier record is **corrected**: [ticket 39](39-create-account-form.md)'s
`if (!opening.isIdle) opening.reset()` loses a refusal that arrives after a keystroke, and
the reasoning is above. Two are **extended** — that ticket's named cost, the zero rule with
nowhere to live, is discharged in the first section, and
[ticket 35](35-idempotency-key-module.md)'s "the module cannot enforce that the payload is
the submitted one" gains the caller that does.

The submitting state is rendered — the button reads *Requesting…* and is disabled — and is
not asserted in a browser, for [ticket 37](37-playwright-harness.md)'s reason: the harness
answers immediately and offers no way to hold a request open. It is in
[deferred.md](../deferred.md) beside the other two.
