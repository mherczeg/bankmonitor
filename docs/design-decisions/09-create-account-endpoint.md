# Ticket 09 — creating an Account

The first business endpoint, and the first request body. §31 said *"`POST
/api/accounts` takes a currency and an initial balance"* and left the rest to
the build; what the build found is that the two things the ticket asked for —
a closed Currency vocabulary in the OpenAPI document, and a refusal that names
the field — pull against each other, because a value the *deserializer* rejects
never reaches Bean Validation.

Touches §16, §18, §30 and §31 of the [initial decisions](00-initial-decisions.md).

---

## The suffix is `MinorUnits`, where §16 wrote `amountMinor`

§16 fixed the wire convention as *"an integer count of minor units, in a field
named `amountMinor`"*. This ticket ships `openingBalanceMinorUnits`,
`balanceMinorUnits` and `availableBalanceMinorUnits` — the same convention with
a longer suffix — and since it is the first ticket to put such a field on the
wire, the shape it picks is the one tickets 13, 32 and 39 will follow.

**`Minor` is an adjective with its noun missing.** `CONTEXT.md` defines the term
as **Minor Units** and lists *"Cents"* and *"Smallest unit"* under *Avoid*, so
`…Minor` is the one spelling of the concept that is neither the domain term nor
a rejected synonym. §16's own argument is that the name is the mitigation —
`"amount": 10050` invites someone to read 10050 forints and the suffix stops
them — and that argument gets stronger, not weaker, when the suffix is the whole
noun phrase the vocabulary already fixes.

The second half is that §16's example is `amountMinor`, for a transfer amount.
There is no `amount` on this endpoint: the two figures are a balance and an
available balance, so the prefix had to change regardless, and a rule stated as
one literal field name does not say what the balance case should be called.

**§16's sentence is not corrected in place**, because it is not false — it names
a field that does not exist yet. What changed is the suffix, and the pointer
under §16 says so.

---

## The typed `Currency` widened ticket 05's contract

`CreateAccountRequest.currency` is the `Currency` enum rather than a `String`.
That is the whole reason the endpoint exists in the shape it does — §26 has the
frontend generating its types from `/v3/api-docs`, and an enum is how the three
denominations this service can quote reach ticket 39's form and ticket 32's
generated types from **one** place rather than being restated in TypeScript.

Confirmed against the running application rather than inferred from the
annotations — springdoc introspects a package-private `record` fine, which was
the open question:

| in the schema | comes from |
|---|---|
| `"enum": ["EUR","USD","HUF"]`, on `CreateAccountRequest` **and** `AccountResponse` | the `Currency` type |
| `"minimum": 0` | `@PositiveOrZero` |
| `required: ["currency","openingBalanceMinorUnits"]` | `@NotNull` |

`AccountSchemaReachesTheDocumentTest` keeps that table honest, and it exists
because the first version of this record asserted the benefit in prose while
nothing in the suite checked it — the cost below is paid by this slice, and a
cost buying an unchecked benefit is the arrangement most likely to rot. It is
verified against its own violation, in the manner ticket 08 established:
retyping `currency` as a `String` (and converting in `openingBalance()`) leaves
**every other test in the suite green** and fails this one with `Failed to
evaluate JSON path "$.components.schemas.CreateAccountRequest.properties.currency.enum"`.
`OpenApiDocumentSpikeTest` is the wider bet that springdoc introspects at all;
this is the narrow claim §26 actually rests on.

**The cost is that Jackson rejects `"GBP"` before Bean Validation ever runs.**
A constraint annotation is checked against a request object that has been
built; an unknown enum name means the object cannot be built at all. So the
rejection arrives as `HttpMessageNotReadableException`, which ticket 05 maps to
`urn:problem:malformed-request` with no field detail at all — and the ticket's
second checkbox asks for a per-field problem document.

**This contradicts ticket 05's rule.** That ticket wrote: a `400` carrying
`HttpMessageNotReadableException` is `MALFORMED_REQUEST`, a `400` from a failed
constraint is `VALIDATION_FAILED`. The rule was drawn when the only unreadable
body anyone had was one that did not parse. It is still the fallback and
`typeOf` is untouched; what `handleHttpMessageNotReadable` adds is a
reclassification in front of it.

**The discriminator is the path.** A `MismatchedInputException` carrying a
non-empty `getPath()` failed *at a location inside the document* — the caller
sent well-formed JSON with one bad member, and telling them the request was
unreadable sends them hunting for a missing brace. JSON that does not parse has
no path and stays `malformed-request`.

Being precise about this, because ticket 05 rejected exception-class mapping and
the check does name a class: `MismatchedInputException` is not what decides the
URN — it is the Jackson type on which `getPath()` is *declared*, so the
`instanceof` is how the path is reached rather than a mapping from type to
outcome. Every instance of it takes both branches depending on that path. What
ticket 05 rejected was reading the URN off the exception's class **instead of**
the status, and the trap it named — `ConversionNotSupportedException`, a
client-error type on a `500` — cannot arise here: this override only ever runs
on a `400` the framework has already chosen. `typeOf`, which is where the
status-driven rule lives, is untouched.

**Rejected — `String currency` plus a custom constraint.** The obvious fix, and
it validates cleanly: the object always builds, so `@NotNull` and a
value-checking annotation both run and the errors come out of the existing
`MethodArgumentNotValid` path with no new handler. It loses the enum from the
OpenAPI document, which is the one thing the typed field was for. Trading §26's
single vocabulary for a tidier error path is trading the requirement for the
implementation detail.

**Rejected — `spring.jackson.deserialization.read-unknown-enum-values-as-null=true`,
with `@NotNull` catching it.** Cheapest of the three: one property, no handler,
and the field name comes out right. Two things are wrong with it. It is global,
so *every* unknown enum anywhere in this API silently becomes null rather than
being refused — a setting whose blast radius is every future request type. And
the message an operator sees for `"GBP"` is **"must not be null"**, about a
field they filled in.

**The messages are written in the advice, not taken from the exception.**
Jackson's own text names Java types and quotes the offending document back at
the caller (`Cannot deserialize value of type
hu.bankmonitor.payments.common.Currency from String "GBP"`). `messageFor` says
what the field will take instead: an enum lists its constants, which is the
whole of what a closed set has to say about itself.

---

## Jackson truncates a decimal into an integer field, by default

This one is not a design decision so much as a default that had to be found.
Measured against the running application, with
`accept-float-as-int` at each setting and everything else as shipped:

| `spring.jackson.deserialization.accept-float-as-int` | `POST {"currency":"EUR","openingBalanceMinorUnits":100.50}` |
|---|---|
| `true` — Jackson's default | **`201`**, `{"id":1,...,"balanceMinorUnits":100,...}` |
| `false` — as shipped | `400 urn:problem:validation-failed`, `errors: [{"field":"openingBalanceMinorUnits","message":"must be a whole number"}]` |

The first row is the finding. An operator who typed a hundred euros fifty gets
an Account holding **100 cents** — a factor of a hundred out, with a `201` and
no warning anywhere. It is the exact mistake `openingBalanceMinorUnits` is
named to prevent, arriving through a channel a field name cannot reach.

So the ticket's *"no reader can mistake `10050` for a decimal quantity"* is
enforced twice: in the name, for the human writing the request, and in the
deserializer, for the one who wrote it anyway. Given §16, a decimal in a count
of Minor Units is a category error rather than a rounding question, which is
why the property is global rather than an annotation on this one field —
`WHOLE_NUMBER_TYPES` in the advice is the matching list of types the message
applies to.

---

## One asymmetry the two paths cannot be made to share

A constraint failure reports **every** bad field; a deserializer rejection
reports **one**. `POST {}` names both missing members, and a body with a bad
currency *and* a decimal amount names whichever Jackson reached first, with the
next attempt naming the other.

That is the deserializer's behaviour and not a choice made here — Bean
Validation runs over a finished object and can walk all of it, while Jackson
stops at the member it cannot build. It is recorded because it is visible from
outside: a client cannot assume `errors` is complete, and a form that clears
every unmarked field on each response would un-mark a field that is still
wrong.

The alternative would be reading ahead in the document to collect further
failures, which means a second parse of the body against the same types and a
plausible source of disagreement between the two passes. Not worth it for a
case that only arises when a caller sends two bad values at once.

---

## The response is *an Account*, not *the created Account*

`AccountResponse` carries the balance **and** the Available Balance, which for
a just-opened Account are necessarily equal — the constructor reserves nothing.
A creation-specific response would drop the second field as redundant.

It is here because ticket 10 lists Accounts with both figures and ticket 39
wants the Account that was just created to appear in that list. Two shapes
generated from one OpenAPI document hand the frontend two Account types, and
the code that puts a fresh `CreateAccountResponse` into a list of
`AccountResponse` is a mapping function written to paper over a distinction the
API invented.

The Reserved Amount itself is left out: it is the difference between the two
figures, and a third number that says nothing the first two do not is a number
that can disagree with them.

> **Superseded by [ticket 10](10-list-accounts-and-seed.md)**, which landed the
> listing first and put `reservedAmountMinorUnits` on the shared shape: the
> figure is what ticket 13's overdraft check tests against, and derived on the
> way out rather than stored, so it cannot disagree with the two it comes from.
> This endpoint answers with that shape, three figures and all.

---

## `Repository`, not `JpaRepository`

`AccountRepository extends Repository<Account, Long>` — the bare marker — and
declares `save` and nothing else. (Ticket 10 adds the listing query and the
seeder's `saveAll` beside it, each against a call site of its own.) Spring Data implements the methods that are
declared, so the alternative is not "the same interface with more convenience":
it is **thirty-seven** inherited public methods (23 distinct names, counted off
`JpaRepository` on the version this build resolves) including `deleteAll`, on
the table that holds every balance in the system.

Ticket 08 refused to write a repository at all, on the grounds that ticket 09
owned the write path and 12 the locking query and a repository written before
either would guess at both signatures. This keeps that discipline on the other
side of it: the interface is the list of what this slice actually does, ticket
10 adds the listing query and ticket 12 the locking one, each against a caller.

---

## Two ways the first repository broke tests that had nothing to do with it

Both are findings about the *test harness* rather than about this slice, and
both are the same shape: a narrowing annotation that narrows less than it
looks like it does.

**`@EntityScan` narrows entities but not repository scanning.**
`MoneyMapsToTwoColumnsTest` and `RecordAsEmbeddableSpikeTest` both point
`@EntityScan` at a test-support host entity, so their contexts deliberately do
not manage `Account`. The moment `AccountRepository` existed, both failed with
`Not a managed type: class hu.bankmonitor.payments.accounts.Account` — Spring
Data's repository scan is a separate mechanism and still found it. Fixed with
`spring.data.jpa.repositories.enabled=false` in each, which is the honest
statement anyway: neither test has anything to say about repositories.

**A bare `@WebMvcTest` loads every controller in the application.**
`ProblemDocumentContractTest` broke the same way — `AccountController` was
picked up, dragging `AccountService` → `AccountRepository` into a slice with no
database behind it. Now `@WebMvcTest(ProblemProbeController.class)`, naming the
probe controller the test was always about.

Worth stating as a rule rather than a fix, because it will happen again with
every controller ticket: **the error contract belongs to no slice**, so its test
should name its own controller rather than sweep up whatever else exists. The
unnamed form would have gone red on ticket 10, 12 and 13 in turn.

---

## No `Location` header on the `201`

Deliberate, and it is the one place this ticket's response knowingly departs
from what a REST checklist would ask for.

Nothing in `.scratch/global-payment-service/issues/` defines a single-Account
resource. Ticket 10 lists Accounts; no ticket fetches one by id, and the
frontend screens read the list. A `Location: /api/accounts/7` would therefore
address a `404` — a header that is worse than its absence, because a client
that follows it fails, whereas one that reads the identifier out of the body
works. The body carries `id`.

Recorded in [deferred.md](../deferred.md) together with the single-Account
endpoint it is waiting on, so that the two are picked up in one go rather than
the header being added to point at nothing.

---

## Smaller calls

- **A zero opening balance is allowed** — `@PositiveOrZero`, not `@Positive`.
  An Account with nothing in it is a thing an operator may reasonably want; a
  negative one is not, and would in any case violate ticket 08's
  `accounts_reserved_within_balance` constraint and reach the caller as a `500`
  rather than as a refusal naming the field.
- **Boxed `Long`, not primitive `long`, on the request.** A primitive that no
  JSON member filled arrives as a perfectly valid `0`, so an absent field would
  open an empty Account instead of being refused. `@NotNull` on a primitive
  cannot fire. `AccountResponse` uses primitives, because on the way out there
  is no absent case.
- **`AccountCreationContractTest` does not import `SecurityConfiguration`.** It
  is package-private in `hu.bankmonitor.payments` and unreachable from the slice
  package, so the test uses `@AutoConfigureMockMvc(addFilters = false)`. The
  claim that `/api/**` answers without credentials is `SecurityChainTest`'s and
  is made against a real chain; `CreatedAccountReachesTheTableTest` goes through
  that chain anyway, so the endpoint's reachability is asserted where the
  filters are real.
- **`openedAccount` sets the identifier with `ReflectionTestUtils`.** Only the
  database hands one out, so a test that stubs the service away has to stand in
  for it. The alternative — a package-private setter or constructor on `Account`
  — is production surface that exists only for a test.
- **`AccountService` stays package-private**, with `open` public. §30's trap:
  `@Transactional` on a non-public *method* is silently ignored under
  proxy-based AOP. It gains a public type when ticket 12 or 13 has a caller
  outside the slice, not before.
