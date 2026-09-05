# 09: Create an Account

**What to build:** An operator can create an Account by naming a Currency and a starting
balance, and gets back the created Account. The Currency must be one of EUR, USD or HUF —
an Account in a Currency the Exchange Rate provider cannot quote is not creatable — and it
is fixed from this moment on.

Amounts cross the wire as an integer count of Minor Units in a field whose name says so,
so that no reader can mistake `10050` for a decimal quantity.

A rejected creation must say *which field* was wrong, using the field-level problem
extension from ticket 05, so the form can mark exactly what to fix rather than showing one
sentence.

**Blocked by:** 05, 08

**Status:** done

- [x] Creating an Account returns `201` with the created Account
- [x] An unknown Currency is rejected with a per-field problem document
- [x] A negative starting balance is rejected with a per-field problem document
- [x] The wire field for the amount is an integer Minor Unit count and named accordingly
- [x] Web-layer tests cover the status codes and problem bodies without a database

## Comments

**The ticket's own two requirements pull against each other, and that is the finding.**
It asks for a closed Currency vocabulary (so §26's generated types read the three
denominations from the OpenAPI document rather than restating them) *and* for a refusal
that names the field. Typing `currency` as the `Currency` enum gets the first —
`/v3/api-docs` shows `"enum": ["EUR","USD","HUF"]` on both schemas, `"minimum": 0` from
`@PositiveOrZero` and `required: [currency, openingBalanceMinorUnits]` from `@NotNull`,
all confirmed against the running app, and springdoc introspects a package-private
`record` fine — and `AccountSchemaReachesTheDocumentTest` now pins it, because the cost
below is paid by this slice and nothing in the suite was checking that the benefit
arrived. Retyping `currency` as a `String` leaves every other test green and fails only
that one. But the typed field means Jackson refuses `"GBP"` before Bean Validation ever
runs. A
constraint is checked against an object that has been built, and an unknown enum name
means the object cannot be built. The refusal therefore arrived as
`HttpMessageNotReadableException` → `urn:problem:malformed-request`, with no field in it,
failing the second checkbox.

**This contradicts ticket 05's rule** that a `400` carrying
`HttpMessageNotReadableException` is `MALFORMED_REQUEST`. That rule was drawn when the
only unreadable body anyone had was one that did not parse.
`ProblemDocumentAdvice.handleHttpMessageNotReadable` now reclassifies a
`MismatchedInputException` **carrying a non-empty path** as `validation-failed` with one
`errors` entry; `typeOf` is untouched and is still the fallback. The path is the
discriminator: a failure with a location inside the document is a well-formed request with
one bad member, and JSON that does not parse has no path. The `instanceof` in that check
is not a counter-example to ticket 05's rejection of exception-class mapping —
`MismatchedInputException` is the Jackson type that *declares* `getPath()`, so it is how
the path is reached rather than a mapping from class to URN, and every instance of it
takes either branch depending on the path. Ticket 05's trap
(`ConversionNotSupportedException`, a client-error type on a `500`) cannot arise in an
override that only runs on a `400` the framework already chose. Ticket 05's own record
carries a pointer to this.

Two cheaper fixes were rejected. **`String currency` plus a custom constraint** validates
cleanly through the existing path and loses the enum from the OpenAPI document, which is
the one thing the typed field was for. **`read-unknown-enum-values-as-null=true` with
`@NotNull`** is one property and no handler, but it is global — every unknown enum
anywhere in this API silently becomes null — and the message an operator sees for `"GBP"`
would be *"must not be null"*, about a field they filled in. The messages are written in
the advice rather than taken from the exception, because Jackson's own text names Java
types and quotes the document back.

**Jackson truncates a decimal into an integer field by default, and that is measured, not
inferred.** With `accept-float-as-int` at its default `true`,
`{"currency":"EUR","openingBalanceMinorUnits":100.50}` answers `201` with
`"balanceMinorUnits":100` — an operator who meant a hundred euros fifty gets an account
holding 100 cents, with no error anywhere. With
`spring.jackson.deserialization.accept-float-as-int=false` as shipped, the same request is
a `400` naming the field *"must be a whole number"*. That is the ticket's *"no reader can
mistake `10050` for a decimal quantity"* enforced on the way in as well as in the field
name.

**The wire suffix is `MinorUnits`, where §16 wrote `amountMinor`.** This is the first
ticket to put such a field on the wire, so the spelling it picks is the one tickets 13, 32
and 39 inherit, and the deviation is deliberate rather than a slip: `CONTEXT.md` fixes the
term as **Minor Units** and lists "Cents" and "Smallest unit" under *Avoid*, which makes
`…Minor` the one spelling that is neither the domain term nor a rejected synonym. §16's
own argument is that the name is the mitigation, and it gets stronger when the suffix is
the whole noun phrase. There is also no `amount` on this endpoint — the two figures are a
balance and an available balance — so the prefix had to change regardless. §16's sentence
is not corrected in place, because it names a field that does not exist yet; the pointer
under it records the change of suffix.

**`accept-float-as-int=false` is wider than any checkbox asked for**, and worth naming as
such. The line quoted as its authority — *"no reader can mistake `10050` for a decimal
quantity"* — is about the field *name*; the property is application-wide and binds every
request type this service will ever have. Kept anyway: given §16, a decimal in a count of
Minor Units is a category error everywhere, not just here, and the measured `201`-with-100
above is what the alternative costs. An annotation on this one field would have left the
next endpoint to rediscover it.

**One asymmetry a client can see:** constraint violations report every bad field, a
deserializer rejection reports one, because Jackson stops at the member it cannot build.
`POST {}` names both missing fields; a body with a bad currency *and* a decimal amount
names whichever came first. That is the deserializer's behaviour rather than a choice, and
it is stated in the README because a form that clears every unmarked field on each
response would un-mark a field that is still wrong.

**No `Location` header on the `201`, deliberately.** No ticket anywhere in
`.scratch/global-payment-service/issues/` defines a single-Account resource — 10 lists,
nothing fetches one by id — so a `Location` would address a `404`, which is worse than its
absence: a client that follows it fails, whereas one that reads `id` out of the body
works. In `docs/deferred.md` together with the endpoint it waits on, so the two are picked
up in one go.

**The response is *an Account*, not *the created Account*.** `AccountResponse` carries
balance and Available Balance, which for a just-opened Account are necessarily equal, and
a creation-specific shape would drop the second as redundant. Ticket 10 lists Accounts
with both figures and ticket 39 wants the new one to appear in that list; two shapes
generated from one OpenAPI document hand the frontend two Account types. The Reserved
Amount is their difference and would be a third number that can disagree with the first
two.

**`AccountRepository` extends the bare `Repository<Account, Long>` and declares only
`save`.** Spring Data implements what is declared, so the alternative is not the same
interface with more convenience — it is thirty-odd inherited methods including
`deleteAll`, on the table holding every balance. Ticket 08 refused to write a repository
ahead of its call sites; this keeps that on the other side of it, leaving the listing
query to ticket 10 and the locking one to ticket 12.

**The first repository in the codebase broke two tests that had nothing to do with this
slice**, both in the same way: a narrowing annotation that narrows less than it looks
like it does. `@EntityScan` narrows entities but *not* Spring Data's repository scan, so
`MoneyMapsToTwoColumnsTest` and `RecordAsEmbeddableSpikeTest` — which point it at a
test-support host entity — both failed with `Not a managed type: class
hu.bankmonitor.payments.accounts.Account` the moment `AccountRepository` existed; fixed
with `spring.data.jpa.repositories.enabled=false` in each, which is the honest statement
anyway. And a bare `@WebMvcTest` loads *every* controller, so
`ProblemDocumentContractTest` dragged `AccountController` → `AccountService` →
`AccountRepository` into a slice with no database; now
`@WebMvcTest(ProblemProbeController.class)`. The second is worth a rule rather than a fix:
the error contract belongs to no slice, and the unnamed form would have gone red again on
tickets 10, 12 and 13 in turn.

**Smaller calls.** A zero opening balance is allowed (`@PositiveOrZero`); a negative one
would also violate ticket 08's `accounts_reserved_within_balance` and reach the caller as
a `500` rather than as a named field. The request's fields are boxed `Long`, not
primitive — a primitive no JSON member filled arrives as a valid `0`, so an absent field
would open an empty Account instead of being refused, and `@NotNull` on a primitive cannot
fire. `AccountCreationContractTest` uses `addFilters = false` rather than importing
`SecurityConfiguration`, which is package-private in the root package and unreachable from
the slice; the claim that `/api/**` answers without credentials is `SecurityChainTest`'s,
made against a real chain, and `CreatedAccountReachesTheTableTest` goes through that chain
anyway. `AccountService` stays package-private with a public `@Transactional open` — §30's
trap — until ticket 12 or 13 has a caller outside the slice.

Design decision 09 has the full reasoning, both measured tables, and the rejected
alternatives.
