# 35: The Idempotency Key lifecycle

**What to build:** The frontend half of a graded requirement, as a plain module with no
React in it.

An Idempotency Key identifies **what the operator meant to do**, not an individual HTTP
attempt. So:

- It is generated **once**, when the form becomes ready.
- It is **held** across retries — the same key goes out on every attempt of the same intent.
- It is **reset only after a success**. Correcting a rejected amount and resubmitting is a
  new intent, and gets a new key.

**The failure this exists to prevent:** generating the key inside the request function
defeats the entire mechanism — every retry gets a fresh key, the server sees a brand-new
Transfer each time, and the network retry the whole feature exists to make safe becomes the
double-charge it was meant to prevent.

Keys must be well-formed UUIDs; the backend rejects anything else with `400`.

**Blocked by:** 31

**Status:** ready-for-agent

- [ ] A key is generated once per intent and returned stably on repeated reads
- [ ] Repeated failed attempts of one intent reuse the same key
- [ ] A success resets the key; a failure does not
- [ ] Keys are well-formed UUIDs
- [ ] Unit tests cover the retry-reuse and reset-on-success rules with no React
