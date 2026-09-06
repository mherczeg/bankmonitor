# 40: The Transfer screen

**What to build:** The second required screen. Choose a source Account, a destination
Account and an amount; submit; land on that Transfer's own page.

The details that make this screen the interesting one:

- **The amount is denominated in the source Account's Currency automatically**, shown as an
  adornment on the field. There is no Currency input, because there is no way to express a
  Transfer that claims EUR on a HUF Account — the backend derives it from the source
  Account, and the form matches that.
- **The validation schema is built over the accounts list**, so the decimal rule derives
  from the selected source Account. Switching the source from a 2-decimal to a 0-decimal
  Currency **immediately re-validates** an amount like `100.50`, with no manual dependency
  wiring.
- **A Transfer from an Account to itself is refused in the browser** — a cross-field rule,
  so it lives in the form-level validator rather than on either field.
- **An amount of zero is refused in the browser, and this form owns that rule.** Ticket 39
  moved it out of `parseAmount`, which now reads zero as the amount it is — an Account may
  be opened with nothing in it, and a Transfer of nothing may not. Nothing in the frontend
  enforces it until this ticket does; the backend refuses it with a `422` in the meantime.
  See `docs/design-decisions/39-create-account-form.md`.
- **The Idempotency Key comes from the lifecycle module** (ticket 35): `startIntent()` once
  when the form becomes ready, `keyFor(payload)` on every attempt, `succeeded()` when the
  Transfer is accepted. It must not be minted inside the request. Ticket 35 keys on the
  payload, so the key resets on a success *and* on a corrected payload — an amount the
  operator fixes after a refusal is a new intent, and the module hands out a new key for it
  without being asked.
- **`keyFor` is given the payload that attempt sends, captured at submit** — never live form
  state read inside the request function. A retry that re-read a form edited since it went
  out would see a changed intent, mint a fresh key and put a second Transfer on the wire
  while the first is in flight. A TanStack Query mutation satisfies this by default: it
  re-invokes `mutationFn` with the variables `mutate` was called with. Ticket 35's module
  cannot enforce it — it is never told a submit happened — so it is this screen's to keep.
- **Submitting navigates to that Transfer's page**, so its pending state lives in the URL
  and survives a refresh. This is the frontend half of the asynchronous lifecycle: there is
  no "waiting" spinner that loses everything on reload.

Failures are rendered from the problem module, including whether retrying will help.

Carries its own browser spec.

**Blocked by:** 35, 39

**Status:** done

- [x] The amount field shows the source Account's Currency as an adornment
- [x] Switching the source Account re-validates the entered amount immediately
- [x] A self-Transfer is refused without a request being sent
- [x] An amount of zero is refused without a request being sent
- [x] An insufficient Available Balance is reported readably
- [x] Submitting navigates to the Transfer's page
- [x] The Idempotency Key is stable across retries of one intent, and resets on a success or
      a corrected payload — never on a failure alone
- [x] The payload handed to `keyFor` is the submitted one, so a mid-flight edit cannot change
      the key an attempt in progress goes out under
- [x] A browser spec covers submit-and-navigate, the Currency re-validation and the
      self-Transfer refusal

## Comments

**"`startIntent()` once when the form becomes ready" is a sentence about a lifetime, so it
was made structural rather than a discipline.** The route owns the `queryKeys.accounts()`
query and its states; `-newTransferForm.tsx` takes the loaded list as a prop. The form
therefore cannot exist before the list does, and `useRef(startIntent())` runs once per
screen rather than once per render of a spinner. The one-component version would work and
would rest the claim on React's ref semantics across a loading state instead of on the
component's lifetime. It also gives `transferSchemaFor` a non-optional argument, which is
the shape it should have — a schema built over a list that has not loaded would refuse
every Account as unknown.

**The key is read off the mutation's variables, and that is the whole of the retry rule.**
`mutationFn: (transfer) => requestTransfer(transfer, keys.current.keyFor(transfer))` — the
`transfer` parameter, never anything in scope, so there is no form state in that function
to read. TanStack re-invokes it with the variables `mutate` was called with, which makes
the ticket's sharpest checkbox a property of the file rather than a default being relied
on. The **Try again** button re-calls `mutate(requesting.variables)` for the same reason.
Ticket 35's module cannot enforce any of it — it is never told a submit happened — so the
browser spec reads the header instead: **nothing on this screen renders the Idempotency
Key**, so a form minting a fresh one per attempt would pass every assertion a DOM can
carry. The harness gained `headersSent` for that, and Playwright lowercases header names,
so the spec reads `['x-idempotency-key']`; the obvious spelling silently reads `undefined`,
and two `undefined`s compare equal.

**`succeeded()` is called into a form that is already unmounting**, and is called anyway.
`onSuccess` navigates away, so the ref goes with it and the call changes nothing
observable. Skipping it would make this screen depend on routing to end an intent — a
dependency neither module records — and the line that replaced it would be a comment
explaining why the module's contract is not honoured here.

**The self-Transfer sentence lands on the destination.** §22 puts the rule in the
form-level validator and stops there; what is still to choose is which field carries it,
because a two-entry issue path prints it twice. The money is already leaving the Account
the operator chose first, so the destination is the side they will change — attaching it to
the source would be telling them to undo the decision they had made rather than the one
they had not.

**Zero came back, exactly where ticket 39 said it would.** That ticket moved the rule out
of `parseAmount` — an Account may be opened with nothing in it — and named the cost as this
ticket's. It is one line and one sentence here, *"A Transfer has to move more than
nothing."* The negative case is where the two forms visibly diverge: an Account cannot open
owing money, and a Transfer of a negative amount is a Transfer the other way round, so this
one says *"Swap the two Accounts to send it the other way."*

**Two extractions, one from each side of the build.** `formRefusal.ts` takes
`serverRefusalIn` and `messagesUnder` out of `accountSchema.ts` now that a second form
wants them; each schema exports its own binding over its member → field map, so no call
site changed. `testsupport/fetchAnswers.ts` does the same for the `fetch` stub
`api/accounts.test.ts` had inline, on ticket 35's `plainModuleRules` precedent — a second
copy of test apparatus is the moment it becomes a module. What did **not** move is the
copy: three of the four amount sentences match the new-Account form word for word and are
written out again, on ticket 33's rule that `money.ts` owns the rule and each form owns its
wording.

**Rejected — rendering the Available Balance out of the refusal.** The backend puts
`availableBalanceMinorUnits` and `currency` on the `insufficient-funds` document, and
*"the Available Balance is 40.00 EUR"* would be better advice than *"does not cover this
amount"*. Taking it would make a second reader of the document that branches on the URN,
which is §18's single discriminator growing a second one; ticket 34 asserts at source level
that `problem.ts` never reads a status for the same reason. The figure is not lost, only
sourced from the endpoint that owns it — the Accounts screen shows every Available Balance,
and the source `<select>` here shows each Account's beside its identifier.

**Rejected — a client-side cross-currency rule.** Ticket 26 makes a cross-currency Transfer
legal, so a rule written here would be one to delete rather than reword. `problem.ts`
already has wording for the refusal, and it says "yet" for exactly this reason.

**Nothing is invalidated on success.** Ticket 39's form invalidates the Accounts list
because the row it created has to appear on the same screen; this one navigates away, and
ticket 38 settled that there is no `staleTime`, so every screen refetches on mount. An
invalidation here would refetch a list being unmounted.

**The one gap, and it moved rather than repeating.** The button reads *Requesting…* and is
disabled while the `POST` is open, and no spec asserts it — ticket 37's harness answers
immediately. Ticket 39's `deferred.md` entry said the next spec wanting this should build
the held-answer mechanism; this is that spec by count and not by need, since none of the
checkboxes above is about an in-flight state, and the one place a double submit could have
cost something is the one place the Idempotency Key already covers it. The entry now names
ticket 41 instead, whose subject is a state a spec has to be able to hold open.

**Found in review — `reset()` on any non-idle mutation loses a refusal still on its way.**
Ticket 39's `if (!opening.isIdle) opening.reset()` was copied here and is wrong in a window
that form has too: `!isIdle` includes *pending*, and `reset()` detaches the observer from
the running mutation, so a keystroke during an open `POST` leaves the refusal landing on
nothing — no alert, no **Try again**, for a request that did go out. Success survives,
because `onSuccess` is the mutation's own option rather than the observer's, which is why
it is invisible on the happy path. Both forms now reset only on a verdict
(`isError || isSuccess`), which is what the comment beside them always claimed. **Ticket
39's form is corrected in place**, on the precedent that ticket set with `parseAmount`. The
fix has no browser assertion and cannot have one — the harness answers immediately, so
there is no in-flight moment to type into — and `docs/deferred.md` now records that this
gap has cost a real bug rather than an unproven rendering.

**Also from review.** `useRef(startIntent())` evaluates its argument on every render and
keeps the first result; harmless, since ticket 35 mints nothing until a key is read, but
this screen's claim is *"opened once"* and the obvious spelling does not say that. It is
React's lazy-ref idiom now. `schema.parse` moved out of the submit handler's `try`, so the
throw that cannot happen would show as a throw rather than as a submit that silently did
nothing. The two `<select>` sides collapsed into one `AccountField`, and
`formRefusal.ts`'s export is `formRefusalIn` — the two schemas were importing
`serverRefusalIn as formRefusalIn` and re-exporting `serverRefusalIn`, which read
backwards.
