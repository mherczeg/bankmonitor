# 21: The Verdict callback, behind a shared secret

**What to build:** The inbound HTTP endpoint a Check service — or a human approver's tool —
calls to report a Verdict for one Check on one Transfer. It is a **thin adapter** over
`recordVerdict` and holds no logic of its own.

The asymmetry with outbound events is deliberate and worth keeping: outbound needs an
outbox because *we* own the atomicity of "the Transfer committed, therefore the event
exists". Inbound owns no such thing — it only needs to be idempotent, which ticket 20
already made it.

**The one real authorization rule in this build** goes here: internal endpoints sit behind
a configured shared-secret header while the public API is open. Without it, anyone could
approve their own Transfer and walk straight past fraud screening. This is not a
contradiction of the authentication deferral — that declined to model *user* identity;
this is service-to-service trust across a boundary the asynchronous model created.

A separate port for internal traffic is deferred, with its reasoning recorded.

**Blocked by:** 04, 20

**Status:** ready-for-agent

- [ ] Reporting a Verdict with the correct secret advances the Transfer
- [ ] A missing or wrong secret is refused before the domain operation is reached
- [ ] The controller contains no lifecycle logic — it maps a request onto `recordVerdict`
- [ ] An unknown Transfer or Check returns a problem document
- [ ] Web-layer tests cover the secret rule and the mapping
