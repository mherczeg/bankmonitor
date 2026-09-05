# Ticket 04 — security chain and CORS

§2 stubbed authentication and named the settings; this ticket built the chain
and chose the default the section had left open.

Touches §2 of the [initial decisions](00-initial-decisions.md).

---

## Deny-by-default rather than permit-by-default

`anyRequest().denyAll()`, with `/api/**`, `/actuator/health` and the springdoc
paths named above it. The permissive version — `anyRequest().permitAll()` — is
the same thing for today's application and a different thing for tomorrow's:
under it, ticket 21's `/internal/**` prefix is reachable from the moment the
controller is written and stays reachable if its rule is ever removed, while
under this one it is unreachable until an entry exists. Deny-by-default is also
what makes the permits worth reading, since each one is a path someone chose to
open. `SecurityChainTest` asserts effects a caller can see — a status code, a
missing `Set-Cookie` — because a test asserting `csrf().disable()` was called
would only restate the source file.

A denial's body is currently empty rather than an §18 problem document, which
does not matter while nothing is denied on purpose. It becomes ticket 21's to
answer, when `/internal/**` produces the first refusal a caller is meant to
read.

## Rejected alternatives

**Rejected — `WebSecurityCustomizer.ignoring()` on `/api/**`.** It is the total
bypass §28 uses for the mock provider, and here it would forgo the security
response headers as well as the authorization rule. A permitted request still
passes through the chain; an ignored one is not a decision, it is an absence.

**Rejected — leaving `UserDetailsServiceAutoConfiguration` in place.** The
starter generates an in-memory account with a random password and prints it at
every startup under a warning to replace the configuration before production.
Harmless, since nothing authenticates — and precisely the "unfinished, not
decided" reading §2 exists to avoid, printed in the reviewer's console at every
boot. Excluded on the application class.

**Rejected — omitting CORS on the grounds that §20's dev proxy makes the
frontend same-origin.** True for proxied calls and false for every other caller:
Swagger UI, a second frontend origin, any deployment that does not proxy. The
policy is enumerated rather than wildcarded for the same reason the chain denies
by default — a wildcard states no intent, so nothing about it can later be read
as wrong.

## What went to the README instead

The README carries the settings themselves and the two traps they avoid, since
that is what a maintainer inherits. Both traps fail quietly rather than loudly,
which is the only reason they are worth a paragraph anywhere: the `ERROR`
dispatch one returns a plausible wrong status rather than an error, and the
`Retry-After` one is invisible outside a browser.
