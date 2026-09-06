# Ticket 14 — requesting a Transfer over HTTP

The first endpoint that spends money rather than describing it. `POST /api/transfers`
answers `201` with a `PENDING` Transfer whose funds are already reserved, and refuses four
things with `422`.

Touches §4, §5, §6, §18 and §22 of the [initial decisions](00-initial-decisions.md), and
**reverses one decision [ticket 13](13-reserve-funds.md) made deliberately.**

---

## Cross-currency Transfers are refused, and ticket 13's reasoning was half wrong

Ticket 13 built `CrossCurrencyTransferNotSupportedException`, removed it again, and wrote
down why: the correct answer to a cross-currency Transfer is a *conversion*, §15 already
says where the rate comes from, and a refusal added early is **a rule ticket 26 has to
reinterpret rather than delete.** The hole went into `deferred.md` with its residual risk
stated.

That objection is answered rather than overruled, and the answer is in the URN.
`urn:problem:cross-currency-unsupported` names a **capability this service does not have**,
not a rule it enforces. Ticket 26 does not reinterpret "cross-currency transfers are
refused" into "cross-currency transfers are converted" — it deletes a refusal that has
stopped being true, the way any *not yet* is deleted when the *yet* arrives. A URN reading
`cross-currency-not-allowed` would have earned ticket 13's objection exactly.

What tipped it is that ticket 14 is where the alternative becomes visible. Ticket 13 could
write a wrong `PENDING` row into a table nothing read. Ticket 14 hands a client a `201` and
a document saying a hundred euros will arrive at a forint Account, which is a promise this
service will not keep.

**The half of ticket 13's argument that was simply false** is in its `deferred.md` entry,
which offered and dismissed a stopgap: *"ticket 14 refusing the request at the endpoint, on
an unlocked read of the two accounts. That read is advisory rather than load-bearing, so it
does not violate §6 — but it is a check that has to be deleted again in 26."*

No unlocked read was needed. The refusal lives in `FundsReservation.reserve`, **after
`lockForTransfer` and before `Account.reserve`** — the first moment both Currencies are
known and the last at which nothing has been written. It costs no query: the Accounts are
already in hand under their locks.

That ordering is the whole of it, and it is not cosmetic. `Account.reserve` raises the
Reserved Amount on an entity attached to the transaction, so it **is** the write; a refusal
one line later has already recorded the reservation it meant to prevent, with the
surrounding rollback the only thing hiding it. `RefusedTransfersWriteNothingTest` asserts
the Reserved Amount is untouched rather than asserting only the exception, which is the
assertion that fails if the check ever moves.

The `deferred.md` entry is **removed**, not amended. It described a hole that no longer
exists, and cross-currency support is ticket 26's scheduled work rather than something
consciously left out.

### One of ticket 13's tests was removed rather than retargeted

`ReservedFundsReachTheTableTest.refusesATransferFromAnAccountToItself` asserted
`IllegalArgumentException` — `AccountLocking`'s backstop — reached *through*
`FundsReservation`. Ticket 14 puts a refusal in front of the lock, so that path no longer
reaches the backstop, and the test was **deleted** rather than pointed at the new exception.

Retargeted, its claim would have been "no rows were written", which
`RefusedTransfersWriteNothingTest.refusesASelfTransferBeforeTakingALock` already contains:
that test asserts the repositories were *not called at all*, and a refusal that reaches no
repository cannot have written a row. Ticket 13 deleted a test on exactly that reasoning —
a claim that is a subset of a sibling's is a second thing to keep passing, not a second
piece of evidence. The backstop keeps its own coverage in
`AccountsLockInAscendingIdOrderTest.refusesATransferWithOneAccountOnBothSides`, where it is
reached directly.

**The duplication is deliberate.** The guard in `accounts` exists so a future caller that
skipped the endpoint fails loudly instead of locking one row twice under two names; the
refusal in `transfers` exists to give a client a `422` it can read. Neither can do the
other's job.

---

## Every refusal is `422`, and `404` was rejected on a sharper argument than taste

An unknown Account, a self-Transfer, insufficient funds and a cross-currency pair are all
`422 Unprocessable Content`. Each is decided against Accounts the request could not see, so
the request was well-formed and this API understood it — which is the distinction `400` and
`422` actually draw.

**A non-positive amount is not among them.** The ticket's checkbox lists it beside the
self-Transfer and the unknown Account, and it still answers `400` with
`urn:problem:validation-failed` — the same document a missing field gets. It is decided from
the payload alone, which is the line the two statuses draw, and §18's `errors` array already
names `amountMinorUnits` as the field that was rejected. A URN of its own would split a
category whose members all have one shape, and would raise the same question for every
constrained field ticket 15 onwards adds.

**Rejected — `404` for an unknown Account.** It is the conventional answer and it is wrong
here. This API already answers `404` with one meaning, settled in ticket 05: *the path names
nothing*. `/api/transfers` always exists. Reusing the status for an Account ID inside a body
gives one status two meanings, and a client could not tell "you posted to a URL that isn't
there" from "account 900 doesn't exist" without parsing the URN it would then need anyway.

---

## The Idempotency Key is required from here, and shaped to fail like a field

§5 puts the guarantee behind the key in ticket 17 and the record in ticket 16. Neither is
built. The key is nonetheless **required** here, and read by nothing.

That is the point. A version of this endpoint that let a client omit the key is a version
clients get written against, and those are the clients still in production when the
guarantee arrives. The contract has to precede the mechanism, or it cannot arrive without
breaking someone.

**The header is declared `required = false` and then annotated `@NotNull @UUID`**, which
reads like a mistake and is not:

| declaration | what a missing header produces |
|---|---|
| `@RequestHeader("X-Idempotency-Key")` | `MissingRequestHeaderException` → `400` with **no `errors` entry** |
| `required = false` + `@NotNull` | a constraint violation → `400` with an `errors` entry naming `idempotencyKey` |

Both are `400`. Only the second produces the document shape ticket 09 established for a
rejected body field, so a client has one way to read every rejection rather than two. A
malformed key takes the same path, via `@UUID`.

### `@UUID` on its defaults accepts less than its name

Hibernate Validator's `@UUID` is not "is this a UUID". Read off
`hibernate-validator-9.1.3.Final` with `javap -v`:

| member | default | what it excludes |
|---|---|---|
| `version()` | `{1, 2, 3, 4, 5}` | **UUIDv6, v7 and v8** — v7 is now the default in several client libraries, because it sorts by time |
| `letterCase()` | `LOWER_CASE` | **every uppercase key**, which is the same 122 bits after a normalising proxy or an uppercasing log |
| `variant()` | `{0, 1, 2}` | only variant 3, which is reserved |
| `allowNil()` | `true` | nothing — the all-zero UUID passes |

So the bare annotation answered `400` to two kinds of well-formed key, against the ticket's
"a key that is not a well-formed UUID is `400`", which implies one that is, is not. Both
were reproduced as failing tests before the annotation was touched.

The fix spells the versions out — `version = {1, …, 15}`, the full range the annotation
permits — because there is no value meaning *any*: `checkAndSortMultiOptionParameter`
asserts the array is non-empty, so `version = {}` raises an `IllegalArgumentException` when
the validator initialises rather than widening it. `letterCase` becomes `INSENSITIVE`.

`allowNil` is turned **off**, which is the one place this narrows the rule rather than
widening it, and it follows from why a key has to be a UUID at all. Keys are globally
scoped ([deferred.md](../deferred.md)), so the only thing between a caller and squatting on
someone else's key is that guessing one is a 122-bit problem. The nil UUID is the single
value that argument does not cover, and it is what a client that forgot to generate a key
arrives with.

---

## The refusal handlers live on the controller, and this replaced an ordering trap

The four `@ExceptionHandler` methods sit on `TransferController` itself.

**Rejected — a slice-local `@RestControllerAdvice`**, which is what was built first and then
removed. It works only with `@Order` on it, and the reason was measured rather than assumed:
Spring sorts every `@ControllerAdvice` and asks them in order, taking the first that has a
handler for the thrown type. `ProblemDocumentAdvice` declares a handler for `Exception` and
declares no order, so it sorts at `Ordered.LOWEST_PRECEDENCE`. An unordered slice advice
sorts there too — equal keys, stable sort, winner decided by classpath scan order. Deleting the annotation and running the suite turned all four refusals from `422`
into `500`, logged at ERROR as genuine faults.

An annotation was enough to fix it, and that is the objection rather than the defence: the
number on one class means something only against an unstated default on a class in another
package, neither file names the other, and the failure is a plausible-looking `500` rather
than a crash. Tickets 15 and 21 add controllers to this slice, so whatever is chosen here is
a precedent — and a precedent reading *"remember `@Order`, and remember why"* is one that
gets forgotten once.

Handlers on the controller are reached by the structure of the lookup instead.
`ExceptionHandlerExceptionResolver` searches the controller's own class hierarchy first and
consults advice beans **only if that finds nothing**. Nothing to order, nothing to forget,
and no test needed to defend either.

**Rejected — `ErrorResponseException` on the refusals themselves**, so each exception
carries its own status and problem document and `ProblemDocumentAdvice` renders it through
the hook its Javadoc already describes: *"a document that already names its type keeps it."*
It is the most idiomatic Spring answer and it removes the ordering too, because within a
*single* advice class Spring matches by type specificity rather than by declaration order.
It was rejected on layering: `UnknownAccountException` and `InsufficientFundsException` live
in `accounts`, so this would put an HTTP status in the `accounts` slice and let it decide
what a refused *Transfer* looks like. It also pins one status per exception for good, which
is exactly what the `422`-not-`404` argument above says is contextual.

The cost accepted: handlers on a controller serve that controller only. A second controller
in this slice needing the same refusals would duplicate them, and at that point the advice
comes back with its `@Order` — justified by a second caller rather than by a precedent set
on the first.

### `instance` was being set by hand, and never needed to be

The advice set `problem.setInstance(URI.create(request.getRequestURI()))` on every refusal,
justified as *"the base class that would otherwise do it is the one this advice deliberately
is not."* That is wrong about the mechanism. `RequestResponseBodyMethodProcessor` fills
`instance` in from the request URI for **any** `ProblemDetail` that does not name one,
whoever produced it and from whatever class. The line is gone, all four handlers shed an
`HttpServletRequest` parameter, and `TransferRequestContractTest`'s assertion that
`instance` is `/api/transfers` still passes — which is the proof, not the reasoning.

---

## A `Clock` bean, because no slice owns "now"

`ReservationRequest` carries the instant the Transfer was asked for, and something has to
supply it. `Clock.systemUTC()` is declared on the application class beside the security
chain and the problem document advice — the other two things belonging to no slice. Boot
auto-configures no `Clock`, so it is declared rather than merely injected.

Injecting it rather than calling `Instant.now()` is what makes the timestamp assertable, and
ticket 23 needs the same seam for a sharper reason: every check deadline is measured from
this instant, and a test that had to sleep through one would be slow *and* unable to say
what it was waiting for.

---

## The wire says `fromAccountId`; every Java type says source and destination

§22 fixes the payload as `{ fromAccountId, toAccountId, amountMinor }`, and the frontend's
transfer form — ticket 40 — will be written against it. The domain vocabulary in [CONTEXT.md](../../CONTEXT.md) is
*source* and *destination*, and `ReservationRequest`, `LockedAccounts`, `Transfer` and
`AccountLocking` all use it.

The two are allowed to disagree, and the seam is `CreateTransferRequest.reservationAt` —
the only place both vocabularies appear. `TransferResponse` carries the wire names too, so a
client reads the Transfer it just posted back in the words it posted it with.

The amount is `amountMinorUnits` rather than §16's `amountMinor`, following ticket 09's
suffix convention: `10050` in a field called `amount` reads as ten thousand and fifty to one
caller and a hundred and fifty to the next. The convention is already on the wire in
`AccountResponse`.

**There is no Currency in the payload**, per §22 — it is derived from the source Account
under its lock. A client claiming EUR on a HUF Account cannot be expressed, rather than
being validated away.

---

## What this ticket deliberately did not build

- **The idempotency guarantee.** Only the contract. Ticket 16 brings the record, ticket 17
  the resolution; §4 puts the record's flip to `SUCCEEDED` in the same transaction, which is
  the `@Transactional` already on `FundsReservation.reserve`.
- **A `Location` header on the `201`.** Ticket 15 gives a Transfer a resource of its own,
  and the header belongs with it rather than pointing ahead of it. This inverts ticket 09's
  reasoning, where the Account's own endpoint was the thing that did not exist yet.
- **An OpenAPI schema test for the two new records.** No checkbox asks for it;
  `AccountSchemaReachesTheDocumentTest` is the precedent if it is wanted later.
