# 04: Security configured, not disabled

**What to build:** A stateless Spring Security chain where every setting is a decision
someone can defend, rather than a blanket disable. CSRF off because the API is stateless
JSON with no cookies; sessions stateless; CORS enabled for the frontend dev origin; the
public API permitted to all.

This is not a contradiction of the authentication deferral. That deferral declined to
model *user* identity. This ticket is about the shape of the chain, so that "auth is out
of scope" reads as a decision rather than an omission.

**Watch out:** Security runs before MVC and intercepts the CORS preflight, so this needs
both the CORS entry on the chain *and* a `CorsConfigurationSource` bean. The annotation
on a controller alone will not work.

The one real authorization rule — the shared secret on internal endpoints — lands with
the endpoint it protects (ticket 21), because there is nothing to protect yet.

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] The public API is reachable without credentials
- [ ] CSRF is disabled, sessions are stateless, and each is justified in a comment
- [ ] A cross-origin preflight from the frontend dev origin succeeds
- [ ] Each setting's reason is recorded, not just its value
