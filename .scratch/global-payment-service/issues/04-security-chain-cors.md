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

**Status:** done

- [x] The public API is reachable without credentials
- [x] CSRF is disabled, sessions are stateless, and each is justified in a comment
- [x] A cross-origin preflight from the frontend dev origin succeeds
- [x] Each setting's reason is recorded, not just its value

## Comments

**The chain denies by default.** The ticket asks for the public API permitted to all,
which `anyRequest().permitAll()` satisfies today and misreads tomorrow: under it, ticket
21's `/internal/**` is reachable the moment its controller exists and stays reachable if
its rule is ever deleted. `anyRequest().denyAll()` with `/api/**`, `/actuator/health` and
the springdoc paths named above it says the same thing about today and a stronger thing
about the next prefix — and it is what makes the permits worth reading, since each one is
a path someone chose to open.

**Deny-by-default has a trap the permissive version hides, and it would have landed on
ticket 05.** Boot registers the security filter for the `REQUEST`, `ERROR` and `ASYNC`
dispatches, so a 404 or a 405 under a permitted prefix is re-dispatched to `/error`,
denied *there*, and returned as an empty `403`. Nothing errors; the caller just sees a
plausible wrong status, and every problem document ticket 05 is about disappears behind
it. `dispatcherTypeMatchers(ERROR, ASYNC).permitAll()` goes first in the chain, and
`missUnderThePublicApiIsNotFoundRatherThanDenied` is the test that keeps it there.
`ASYNC` is in that list for ticket 30: an SSE response is served on an async dispatch,
which is the continuation of a request already authorized on its way in.

**Adding the starter created an account nobody asked for.** `spring-boot-starter-security`
brings `UserDetailsServiceAutoConfiguration`, which generates an in-memory user with a
random password and logs it at every startup under a warning that the security
configuration must be replaced before production. Left in, the first thing the reviewer
sees on boot contradicts the ticket. Excluded on the application class, with a test that
no `UserDetailsService` bean exists.

**`Retry-After` had to be named in `exposedHeaders`.** Only the CORS-safelisted response
headers reach cross-origin JavaScript, and ticket 05 makes that header part of the error
contract — its presence or absence is how a client learns whether retrying will help. The
failure without it is browser-only: the header is on the wire, `curl` shows it, and the
frontend reads it as absent. Cheaper to settle here, next to the CORS configuration, than
to rediscover from the frontend in ticket 31.

**The CORS mapping is `/api/**` only.** `mockfx` stands in for a third party reached
server-to-server (ticket 24), and `/internal/**` is called by other services rather than
by a browser; neither has an origin to allow.

**Worth knowing while reading this:** ticket 31's Vite dev proxy makes the dev frontend
same-origin for proxied calls, so CORS is not on the critical path for the happy dev
setup. It is what makes a direct call to `:8080` work — from Swagger UI, from a second
origin, from a deployment that does not proxy — which is why the policy is enumerated
rather than either omitted or wildcarded.

**One edge deliberately left open:** a denial's body is empty rather than an RFC 9457
problem document. Nothing is denied on purpose yet, so there is no caller to read it;
ticket 21 produces the first refusal that is meant to be read, and the decision belongs
there.
