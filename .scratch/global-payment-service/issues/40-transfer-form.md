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

**Status:** ready-for-agent

- [ ] The amount field shows the source Account's Currency as an adornment
- [ ] Switching the source Account re-validates the entered amount immediately
- [ ] A self-Transfer is refused without a request being sent
- [ ] An amount of zero is refused without a request being sent
- [ ] An insufficient Available Balance is reported readably
- [ ] Submitting navigates to the Transfer's page
- [ ] The Idempotency Key is stable across retries of one intent, and resets on a success or
      a corrected payload — never on a failure alone
- [ ] The payload handed to `keyFor` is the submitted one, so a mid-flight edit cannot change
      the key an attempt in progress goes out under
- [ ] A browser spec covers submit-and-navigate, the Currency re-validation and the
      self-Transfer refusal
