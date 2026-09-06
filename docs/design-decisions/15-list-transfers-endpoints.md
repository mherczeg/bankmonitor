# Ticket 15 — reading Transfers back

The first endpoints that only describe. `GET /api/transfers` lists every Transfer in every
status newest first, `?status=` narrows it, and `GET /api/transfers/{id}` addresses one.

Touches §19 and §31 of the [initial decisions](00-initial-decisions.md), and pays a debt
[ticket 14](14-request-transfer-endpoint.md) deliberately left: the `201` from
`POST /api/transfers` now carries a `Location`.

---

## The read side got a repository of its own, to keep an ArchUnit rule at full strength

`TransferLookup` needs to query Transfers. The obvious shape — more methods on
`TransferRepository` — does not compile past
`NothingButTheReservationCreatesATransferTest`, which forbids **any** class but
`FundsReservation` from *depending on* that interface. The rule is deliberately by
dependency rather than by call, on the grounds that the repository has exactly one write
method and a class holding it is one edit away from calling it.

The rule's own Javadoc had anticipated this ticket, but only half of it: *"matching the
type also survives the query methods tickets 15, 20 and 23 will add to it."* It survives
new **methods**; what it does not survive is a second **class**, which is what a read side
actually is.

So `TransferRepository` was split. It keeps `save` and nothing else and stays
`FundsReservation`'s alone; the new `TransferQueries` carries `findById` and the two
listings, and anything in the slice may hold it. The invariant the rule protects — *a
Transfer comes into being in exactly one place* — is now enforced by the compiler for
readers as well: `TransferLookup` cannot call `save`, because it cannot name it.

**Rejected — exempting `TransferLookup` in the rule.** One line, no production change, and
it concedes exactly the margin the rule was bought for. It also does not stay one line:
tickets 20 and 23 each add a reader, and the rule decays into a list of names that has to
be re-read to know what it still claims.

**Rejected — narrowing the rule to `save` calls.** The rule's author considered and
rejected this shape when writing it. It keeps one interface, and it gives up the "one edit
away" property: a class may then hold the repository indefinitely, and the day someone adds
the call the rule notices, which is a day later than the dependency form.

The cost accepted: two Spring Data interfaces over one entity, which reads as duplication
until you know why. Both Javadocs say why, and the arch rule's Javadoc now names
`TransferQueries` as what pays for its strictness.

---

## Both `GET`s live on `TransferController`, which is what keeps ticket 14's precedent working

Ticket 14 put its four `@ExceptionHandler` methods on the controller rather than in a
slice-local `@RestControllerAdvice`, because an unordered advice loses a stable sort against
`ProblemDocumentAdvice` and turns `422` into `500`. It accepted one cost explicitly:
*"handlers on a controller serve that controller only. A second controller in this slice
needing the same refusals would duplicate them, and at that point the advice comes back with
its `@Order`."*

This ticket is the first test of that, and it passes because the two `GET`s go on the
**existing** controller. `UnknownTransferException`'s handler sits beside the four refusals
and is reached by the same lookup. Nothing is ordered and nothing is duplicated.

Tickets 21 and 22 inherit the same reading: a second controller in `transfers` is the case
that forces the advice back, so it is worth a deliberate decision rather than a reflex.

---

## `404` for an unknown Transfer ID is ticket 05's `404`, not a second meaning

Ticket 14 argued at length that an unknown *Account ID in a body* must be `422`, because
this API already spends `404` on one meaning settled in ticket 05: **the path names
nothing**.

`GET /api/transfers/900` is that meaning, exactly. The identifier is the path, and a path
naming no resource is what `404` has always said here. The two are not in tension; the same
distinction produces both answers.

The document carries a `transferId` extension member, on the precedent of the `accountId`
member ticket 14's `422`s carry.

---

## `?status=BOGUS` reads like a rejected enum in a body, which took a shared-file change

`ProblemDocumentAdvice` — the one file outside this slice the ticket touched — gained a
`handleTypeMismatch` override.

Without it, a query parameter that fails to convert answers `400` with **no `errors`
entry**, while the same unknown enum name in a request body gets one. The class's own
Javadoc says the document's shape must not depend on where the rejected value arrived, so
the gap was a defect against a rule already written down rather than a new decision.

Both handlers now end in one shared `rejectedValue(response, member, target)`, which is
where the type, the detail and the single-element `errors` list are set. They had been two
copies of that block differing only in where the member's name and its failed type are read
from, which is a shape that stays identical by accident until it doesn't — and "identical"
is the entire claim the override exists to make.

It reuses the existing `messageFor`, so the two conversions this API can now fail read:

| request | `errors` entry |
|---|---|
| `GET /api/transfers?status=BOGUS` | `must be one of: PENDING, SETTLED, REJECTED, EXPIRED` |
| `GET /api/transfers/abc` | `must be a whole number` |

---

## The `Location` header, and why it is relative

Ticket 14 deferred it in these words: *"Ticket 15 gives a Transfer a resource of its own,
and the header belongs with it rather than pointing ahead of it."* `deferred.md`'s Account
entry makes the same argument as a rule — *the endpoint and the header together, in that
order* — so shipping the header here is the rule being followed rather than scope added.

The reference is **relative** (`/api/transfers/{id}`), not absolute. An absolute one is
built from the request's own `Host`, and in development this app sits behind a Vite proxy:
the header would name the backend's port and send the browser out of the origin it is
allowed to talk to. RFC 9110 has permitted a relative reference in `Location` since it
replaced RFC 7231's absolute-URI requirement.

### The `PATH` constant cannot be private, and the Javadoc that said otherwise was wrong

A `PATH` constant now backs both `@RequestMapping` and the header, so the two literals
cannot drift. It was first written with a Javadoc claiming it was *"deliberately not visible
to the tests"*. That was false twice over: the constant is package-private and every test in
this slice sits in the same package, so they can read it — and it **cannot** be made
private, because a class-level annotation may not reference a private member of its own
class. Measured, both spellings:

| declaration and reference | `javac` |
|---|---|
| `private static final PATH` + `@Ann(Probe.PATH)` | `error: PATH has private access in Probe` |
| `private static final PATH` + `@Ann(PATH)` | `error: cannot find symbol` |
| `static final PATH` + `@Ann(Probe.PATH)` | compiles |

So the visibility is forced by the annotation, not chosen. The class Javadoc's existing rule
— that *tests* write the path out rather than sharing a constant with the code they check —
still stands, and is now stated as the convention it is rather than as a guarantee the
compiler was falsely credited with enforcing.

---

## `TransferResponse` declares its required members

Ticket 14 left this: *"an OpenAPI schema test for the two new records. No checkbox asks for
it; `AccountSchemaReachesTheDocumentTest` is the precedent if it is wanted later."*

Later is now, and the reason is ticket 32 rather than tidiness. Frontend types are generated
from this document, three screens read this shape, and springdoc marks every member optional
unless told otherwise — so without `@Schema(requiredProperties = …)` every field arrives in
TypeScript as possibly-undefined and each screen writes guards against states the backend
cannot produce.

---

## Two smaller things the tests settled

**The listing orders by `created_at DESC, id DESC`, not `created_at` alone.** The tie break
is part of the contract, not a detail of the query: `created_at` is stored to microseconds
and nothing spaces requests apart, so two Transfers sharing an instant is ordinary rather
than contrived, and without a total order the list reshuffles itself between two refetches
of unchanged data. It is also the key `deferred.md` already names for cursor pagination,
so the ordering this endpoint ships with is the one pagination will need.

**No index on `created_at`.** Deliberate. The demo dataset is tens of rows, and the index
belongs with the pagination ticket that will have a query shape to design it against —
added now it would be a guess defended by nothing.

---

## Both new tests write their rows as SQL, from one fixture

`TransferListingTest` and `OneTransferByIdTest` insert Transfers directly rather than going
through `FundsReservation`, because a Transfer the application can produce is `PENDING` and
nothing else: design decision 11 leaves each transition to the ticket that has a caller for
it, and a listing that reports four statuses cannot be exercised by a path that reaches one.
`AccountListingTest` recorded the same reasoning for the Available Balance.

The row-writing itself is `testsupport/TransferRows`, not a copy in each class. The two
tests had been carrying the same four helpers and the same two Account constants
byte-for-byte, Javadoc included — the duplication a review names before it has cost
anything, and the kind that is repaired by tickets 20 and 23 adding a third copy.

`startFromTwoAccountsAndNoTransfers` merges the delete and the insert into a **single**
`@BeforeEach` on purpose. JUnit promises no order between two of them, and the order that
loses deletes the Accounts the Transfers' foreign keys still need. The fixture's methods are
static for the same family of reason: an injected fixture would have to be built in a
`@BeforeEach`, which is precisely what an `@AfterEach` must not depend on having run.

---

## `?status=` with no value is no filter, and now says so

Spring's enum conversion treats an empty string as an absent value, so `?status=` reaches
the handler as `null` and lists everything. That was the framework's behaviour rather than a
decision, and nothing stated it.

It is kept, and asserted. A form that submits every field whether or not the user touched
it sends exactly this, and answering an untouched dropdown with a `400` would be wrong. The
alternative — rejecting it as "an unknown value" per the ticket's checkbox — reads the
checkbox as being about the *parameter being present*, where it is about a **value this
domain has no name for**, and the empty string does not name a status wrongly; it names none.
