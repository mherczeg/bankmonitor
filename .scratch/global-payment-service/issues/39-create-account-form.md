# 39: Creating an Account from the screen

**What to build:** The create half of the Accounts screen: choose a Currency from EUR, USD
or HUF, enter a starting balance in that Currency's familiar decimal form, submit, and see
the new Account appear in the list.

The form validates with a schema, and the decimal-places rule follows the chosen Currency —
`100.50` is valid for EUR and invalid for HUF. The decimal → Minor Unit conversion happens
at submit, using the formatting module, because schema transforms do not flow through
validation: the submit handler receives input-typed data and converts it explicitly.

Server-side rejections are shown **per field**, using the field-level detail the backend's
problem document carries, so an operator sees what to fix rather than one sentence.

Carries its own browser spec.

**Blocked by:** 34, 38

**Status:** done

- [x] Submitting a valid form creates the Account and the list reflects it
- [x] Currency choice is limited to EUR, USD and HUF
- [x] Entering more decimals than the chosen Currency allows is refused in the browser
- [x] A server-side field rejection is shown against that field
- [x] The amount leaves the browser as a Minor Unit count
- [x] A browser spec covers the happy path, a client-side rejection and a server-side one

## Comments

**A rule had to leave the module that owned it, and the ticket that found it was not the
ticket that broke it.** `parseAmount` refused zero, on ticket 33's reasoning that parse's
domain is "the sums an operator can submit". Ticket 09 had already settled that a zero
opening balance is allowed — *"an Account with nothing in it is a thing an operator may
reasonably want"* — so building this form on `parseAmount` as the ticket directs would have
hidden an API capability behind a client-side rule nobody chose. The rule moved rather than
becoming an option: `parseAmount` now refuses only a negative, `not-positive` is renamed
`negative`, and whether zero is usable belongs to the form asking. **Ticket 40 inherits the
"more than zero" rule for a Transfer and has gained the checkbox**, because a rule that
moved and was not re-landed is a rule that was deleted. Design decision 39 has the rejected
alternative — an opt-in third argument, which keeps the module holding two policies for two
callers to choose between.

**Registering the schema under both `onChange` and `onSubmit` prints every message twice.**
The obvious wiring — validate as they type, and validate again on a form nobody touched —
put *"Enter the amount this Account opens with."* under the field twice. TanStack's default
validation logic runs the change and blur validators on submit as well as the submit one,
and each lands under its own key in the field's `errorMap`, which `errors` flattens. §22
had written `validators: { onChange: schema }` and was right; the second registration bought
nothing. De-duplicating at render was rejected — it fixes the screen and leaves the
configuration that will hide the next validator's output.

**The server's field messages live on the mutation rather than in the form's error state.**
TanStack has an `onServer` channel for exactly this and it was tried and dropped: an entry
there persists until something clears it, and the framework auto-clears only `onSubmit`
errors on change — so an operator would correct the amount and go on reading a refusal of
the one they replaced. Deriving them from `opening.error` makes them last exactly as long as
the failed request, and one `listeners.onChange` calling `opening.reset()` voids the refusal
and the success note together. The browser spec pins the clearing, which is the half a
screenshot of a correct-looking error would never show.

**Two modules read one document, and the second one is not allowed to read the first one's
member.** `validation.ts` sits beside `problem.ts` rather than inside it: the URN chooses
the heading, `errors` chooses what appears under each input, and folding them together would
grow the second discriminator §18 made one URN to prevent. Ticket 34's technique is reused —
the test reads its own subject's source and asserts it names neither the URN nor the response
code — and the edit it forbids is a plausible one: *"only show field detail when the URN is
`validation-failed`"* would discard detail the backend chose to send with some other refusal.

**The wording rule runs in both directions on this screen, which looks inconsistent and is
not.** The heading is `problem.ts`'s words, per ticket 34. The messages under each field are
`errors[].message` verbatim. A URN names a situation there are fourteen of and this app can
have an opinion about; a field message names a constraint, and rewording it would mean
holding a copy of every constraint the backend declares.

**The Currency is named twice on one form, deliberately.** Ticket 33 predicted the screens
would carry the Currency themselves and left a column and an adornment open; ticket 38 spent
the column and assigned the adornment to ticket 40. This form has both, because the refusal
an operator most often meets here is *"HUF is written without decimal places"* and it is only
readable if the denomination is beside the box being typed in — not in a select already
scrolled past.

**Two things the checkboxes did not name.** `mutations: { retry: false }` is written into
`queryClient.ts` for ticket 38's reason — it is the current default, and a designed property
should not be able to move under a minor version. What it protects is specific: only the
Transfer `POST` carries an Idempotency Key, so a silent re-send of this one opens a second
Account. And the submit button is disabled only while a request is in flight, never for the
form being invalid, so a first press on an untouched form answers with every rule at once
rather than with a dead button.

**The one gap, recorded rather than left.** The button reads *Opening…* and is disabled
while the `POST` is open, and no spec asserts it — ticket 37's harness answers immediately
and offers no way to hold a request. It is the same gap ticket 38 named for its spinner, and
`docs/deferred.md`'s entry now covers both and says the next spec that wants it should build
the held-answer mechanism rather than defer a third time.
