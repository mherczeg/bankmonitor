# 05: One error format, with the type URN as the sole discriminator

**What to build:** Every error this API can emit is an RFC 9457 problem document, and a
client branches on exactly one field: the `type` URN. Two fields that can disagree is
the failure mode this exists to prevent.

Use the framework's own problem-document support rather than a hand-rolled envelope. The
framework already emits problem documents for validation rejections, unsupported media
types, wrong methods and malformed JSON — a custom shape would be a *second* error
format, not a replacement.

Two additions on top of the default:

- **Field-level validation errors as an extension member.** The default packs every
  violation into one unusable sentence, which no form can mark up.
- **The `type` URNs as constants in `common`**, so the frontend's generated types and the
  backend agree on the vocabulary from one place.

`Retry-After` policy is part of this contract: present where retrying will help, absent
where it will not. Later tickets rely on that distinction being machine-readable.

**Blocked by:** 01

**Status:** done

- [x] A validation failure returns a problem document with per-field detail, not one
      sentence
- [x] Malformed JSON, an unsupported media type and a wrong method all return the same
      document shape
- [x] Problem-type URNs live as constants in one place
- [x] Tests cover the shape at the web layer without starting a database

## Comments

**`spring.mvc.problemdetails.enabled=true` is not in `application.properties`, and that
is the finding worth carrying forward.** The property enables Boot's own
`ProblemDetailsExceptionHandler`, which is annotated
`@ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)`. This ticket has to
declare such a bean to add the extension member, so Boot's handler backs off and the
property becomes a setting nothing reads. Setting it anyway would be a line of
configuration that looks load-bearing and is not. The class Javadoc says so, since the
next person to delete the advice needs to put the property back.

**Rejected — resolving the URNs from a message source.** Spring reads
`problemDetail.type.<exception FQCN>` keys from `messages.properties` and fills `type`
from them with no Java at all, which is the framework-idiomatic answer. It fails this
ticket's own test: the URNs would live in a properties file *and* in the enum the
frontend's generated types are meant to agree with, which is two places for the
vocabulary that exists to have one.

**Five things the ticket did not name and the contract needed anyway** — a catch-all
`Exception` handler, `AccessDeniedException` rethrown so Spring Security still answers
it, a caller-facing detail for the `404`, the `errors` list sorted, and the same member
on a rejected *parameter* as on a rejected body. Each with its reasoning in design
decision 18; they are settled, so they live there rather than here.

**The vocabulary is fuller than today's code emits.** `ProblemType` carries the two
`409`s and the FX `503` as well as the framework-level types, because those three are
named in the spec and the point of the enum is that the frontend has one list to
generate from. Tickets 17 and 25 throw them; the probe controller in `testsupport`
exercises the two `409`s now, which is what lets the `Retry-After` policy — present where
retrying helps, absent where it does not — be a test rather than a promise.

**Left for ticket 32:** springdoc does not know about `ProblemType` or the `errors`
member, so nothing of this contract reaches `/v3/api-docs` yet. The generated types are
where the frontend is supposed to pick the vocabulary up (design decision 26), so that
ticket has to document a problem-document response schema explicitly rather than
assuming the enum is discovered.

**`Retry-After` is left to the throw sites** rather than made a `ProblemType`-owned
refusal factory. There is no throw site in `main` yet, so the factory would be shaped by
a guess about tickets 17 and 25 rather than by them; what this ticket owes those tickets
is a client that can read the distinction, which the two `409` probes pin.

**Left open, and now written down:** a security denial is raised before the dispatcher
and answered with an empty body, so it is the one error that is not a problem document.
[deferred.md](../../../docs/deferred.md) carries it with what closing it would take.

**Added `spring-boot-starter-validation`.** `@Valid` needs a Bean Validation provider on
the classpath; nothing that was already there brings Hibernate Validator.
