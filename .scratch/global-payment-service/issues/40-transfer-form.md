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
- **The Idempotency Key comes from the lifecycle module** (ticket 35), generated when the
  form becomes ready and held across retries. It must not be minted inside the request.
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
- [ ] An insufficient Available Balance is reported readably
- [ ] Submitting navigates to the Transfer's page
- [ ] The Idempotency Key is stable across retries of one intent and reset only on success
- [ ] A browser spec covers submit-and-navigate, the Currency re-validation and the
      self-Transfer refusal
