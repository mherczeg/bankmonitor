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

**Status:** ready-for-agent

- [ ] Submitting a valid form creates the Account and the list reflects it
- [ ] Currency choice is limited to EUR, USD and HUF
- [ ] Entering more decimals than the chosen Currency allows is refused in the browser
- [ ] A server-side field rejection is shown against that field
- [ ] The amount leaves the browser as a Minor Unit count
- [ ] A browser spec covers the happy path, a client-side rejection and a server-side one
