# Ticket 05 — problem detail contract

§18 chose RFC 9457 `ProblemDetail` with the `type` URN as the sole
discriminator, and expected `spring.mvc.problemdetails.enabled=true` to be the
switch. It is not the one this build uses.

Touches §18 of the [initial decisions](00-initial-decisions.md).

---

## As built

`ProblemDocumentAdvice extends ResponseEntityExceptionHandler`, in the root
package beside the security chain, plus a `ProblemType` enum in `common`.

**`spring.mvc.problemdetails.enabled=true` is not in
`application.properties`, and its absence is deliberate.** The property enables
Boot's own `ProblemDetailsExceptionHandler`, which is annotated
`@ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)` — declaring
ours makes Boot's back off, so the property would be a setting with no reader.
Anyone deleting the advice has to put the property back, which is exactly the
sentence the class Javadoc carries.

**Rejected — resolving the URNs from a `messages.properties` message source.**
Spring will read `problemDetail.type.<exception FQCN>` keys and fill `type`
from them without a line of Java, which is the framework-idiomatic answer and
was rejected on the ticket's own terms: the URNs would then live in a
properties file *and* in the enum the frontend's generated types are supposed
to agree with, which is two places for the vocabulary that exists to have one.

**The URN is chosen from the response status, not from the exception class**,
with one refinement: a body Jackson could not read (`urn:problem:malformed-request`)
and a body that failed its constraints (`urn:problem:validation-failed`) are both
`400` and are not the same news. Mapping by exception class looks more precise and
is not: `ConversionNotSupportedException` extends `TypeMismatchException` — a
client-error type — but is a `500`, so the exception-driven version answered a
server fault with a document blaming the caller's input. A client error this API
does not name more precisely gets `urn:problem:client-error` rather than the
nearest-looking URN; a discriminator that guesses is the field that eventually
lies.

Five things the ticket did not ask for and that the implementation needed
anyway:

- **A catch-all `@ExceptionHandler(Exception.class)` → `500`
  `urn:problem:internal-error`.** Without it, an exception no handler claims
  leaves the dispatcher for Boot's `/error` page, which is a *different*
  document shape derived from the exception — a second error format arriving
  through the back door, and one that tells the caller more about this server
  than it should. Its detail is a fixed sentence for the same reason.
- **`AccessDeniedException` is rethrown from its own handler**, so Spring
  Security's translation still runs. Caught by the catch-all it would become a
  `500`, and §10's shared-secret rule would fail as a server error rather than
  a refusal — silently, since nothing would log a mismatch.
- **`NoResourceFoundException`'s detail is replaced.** The framework says *"No
  static resource api/nope."*, which names the handler that ran out of options;
  this API serves no static resources and the caller can act on none of that.
  The path is already in `instance`, so the replacement does not repeat it.
- **The `errors` list is sorted.** Bean Validation does not promise an order,
  and a form that lists its errors differently on each submission looks like a
  bug to the person filling it in.
- **A rejected *parameter* carries the member too**, not only a rejected body.
  Spring reports the two through different exceptions
  (`HandlerMethodValidationException` vs `MethodArgumentNotValidException`);
  handling only the second would make the document's shape depend on where the
  rejected value arrived, which is a distinction the client cannot see. §16's
  idempotency key header is the case that would have hit it.

The extension member is `errors: [{ field, message }]`, with a null `field` for
a class-level rule that belongs to the request rather than to one input — the
shape §22's cross-field validators produce on the server side.

**`Retry-After` stays the throw site's job**, rather than becoming a
`ProblemType`-owned refusal factory that attaches it. The policy is one line —
present on `REQUEST_IN_PROGRESS` and `FX_PROVIDER_UNAVAILABLE`, absent on
`IDEMPOTENCY_KEY_REUSED` — and there is no throw site in `main` yet, so a
factory now would be an abstraction shaped by a guess about §5's and §27's call
sites rather than by them. What this ticket owes them is that the *client* can
read the distinction, which the contract test pins through a probe controller
raising both `409`s.

`ProblemDocumentContractTest` is a `@WebMvcTest` over that probe controller in
`testsupport`: no database, one assertion helper that every case runs through,
so "the framework's failures and ours are the same document" is checked rather
than asserted in prose. Deleting the `@ControllerAdvice` annotation fails all
ten of its tests.
