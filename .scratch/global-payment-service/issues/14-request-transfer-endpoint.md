# 14: Requesting a Transfer over HTTP

**What to build:** The endpoint an API client posts a Transfer to, for same-Currency
Transfers only. It answers `201` with the newly `PENDING` Transfer, reserving funds via
ticket 13.

The payload is the source Account, the destination Account and the amount in Minor Units.
**Currency is deliberately not in the payload** — it is derived from the source Account,
which deletes a whole class of bug where a client claims EUR on a HUF Account.

The Idempotency Key arrives as a request header and is required from this ticket onward:
a missing key is `400`, and a key that is not a well-formed UUID is `400`. The *guarantee*
behind the key lands in ticket 17; the contract that clients must send one starts here, so
no client is ever written against a version that let them opt out.

A Transfer from an Account to itself is refused with `422` before any lock is taken.

**Blocked by:** 05, 13

**Status:** done

- [x] A valid request returns `201` with a `PENDING` Transfer
- [x] A missing Idempotency Key is `400`; a malformed one is `400`
- [x] A self-Transfer is `422` and no lock is taken
- [x] An unknown Account, a non-positive amount and a cross-Currency request each return a
      problem document with the right type URN
- [x] Web-layer tests cover the status codes and problem bodies without a database
