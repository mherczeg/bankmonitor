# Ticket 26 — cross-Currency Transfers, end to end

This is where §4's phase two stops being a parameter nobody passes. [Ticket 07](07-conversion-function.md)
built the arithmetic, [ticket 25](25-exchange-rate-client.md) built the client, and
[ticket 17](17-duplicate-resolution.md) shipped `executeOnce` with phase two deliberately
absent because there was no caller for it. This ticket is that caller, and joining the three
turned out to settle one design question the reachability rule in `accounts` asked, and to
falsify two predictions written down by earlier tickets.

The question was **where the conversion happens** — above the lock or under it — and it was
put to the user rather than decided here, because the two answers differ in what they cost
rather than in what they compute.

Touches §4 and §15 of the [initial decisions](00-initial-decisions.md).

---

## The conversion happens in phase two, above the lock — chosen explicitly, and the ArchUnit rule is why the question was worth asking

The fork was real. Either phase two fetches the rate *and* converts, handing phase three two
finished amounts; or phase two fetches the rate alone and `FundsReservation` calls
`CurrencyConversion` under the lock, where the two Account Currencies it is converting between
are already read and locked.

The second reading is the more natural one to write. It puts the conversion next to the
Accounts it is denominated by, and it means phase two carries a rate rather than a result.
What rules it out is that `CurrencyConversion` and `ExchangeRate` live in
`hu.bankmonitor.payments.fx`, and `LockedPathTouchesOnlyTheDatabaseTest` walks everything
reachable from `FundsReservation` and fails on any dependency into that package —
**including the pure ones**. That rule is not about purity. It is about the fact that a
provider call in this application lives in `fx` by §30, whatever it is built out of, so the
package is the only thing that stays exhaustive as clients come and go. A conversion under the
lock therefore cannot be written without weakening the rule that keeps a third party out of a
transaction holding two row locks.

So phase two does both, and hands phase three a `ConvertedAmounts` — a record in `transfers`
that names no type from `fx` at all. The rate arrives as a bare `BigDecimal` and its timestamp
as a bare `Instant`, which is the point rather than a compromise.

**This is the rule shaping the design rather than being weakened to admit one, and it is worth
recording as such**, because from inside `FundsReservation` the ban on `fx` reads like
over-reach: `CurrencyConversion` is a static function of three values and could not open a
socket if it wanted to. The rule cannot tell that from a client, and a rule that started making
exceptions for the pure types in a package would stop being an argument about anything.

## Two things earlier tickets predicted, and neither happened

Both are recorded rather than quietly corrected, because both were written down as expiries —
a later reader would go looking for the change and not find it.

**[Ticket 13](13-reserve-funds.md) predicted the `LOCK_HOLDERS` entry for `FundsReservation`
would come out here**, on the reasoning that this ticket would give that class an Exchange Rate
port to call in the phase before the transaction, which the reachability rule cannot distinguish
from a call inside it. It does not come out. Splitting the phases into two beans is precisely
what avoids giving `FundsReservation` the port, so the reservation still reaches nothing but the
database and the entry still holds. The Javadoc in `LockedPathTouchesOnlyTheDatabaseTest` said
the entry had an expiry; it now says why the expiry did not arrive.

**[Ticket 25](25-exchange-rate-client.md) predicted the `ExchangeRate` record would be stored
whole**, since it already carries the pair it was quoted for and so cannot disagree with itself.
Two columns are stored instead — `exchange_rate` and `exchange_rate_fetched_at`. Embedding the
record would add `base` and `quote` columns beside `debited_amount_currency` and
`credited_amount_currency`, which is the same fact written twice per Transfer and therefore two
more ways for a row to contradict itself. The pair is not lost: it is the two amount Currencies,
which is where it was already.

Neither §4 nor §15 states anything false, so per `AGENTS.md` both are left alone with a pointer
added.

## The rate and its timestamp are null exactly when the two Accounts share a Currency

Not `1`. A rate of one would read as a quote, and no quote was fetched — the provider was never
called, which is the ticket's own third checkbox. Ticket 25 is emphatic that this application
does not invent facts about somebody else's data, and a rate is the fact an audit trail would
be read for.

The invariant is enforced three times over, which is deliberate rather than belt-and-braces
because there are three ways to write the row and they do not share a path:

- `ConvertedAmounts`' compact constructor, which is what the application's own path builds;
- `transfers_rate_iff_cross_currency` in `V6`, which also covers the rows tests place in SQL;
- `TransferResponse`'s `@Schema(requiredProperties = …)`, which is the same invariant stated
  for the generated client — the two members are omitted from that list, alongside ticket 22's
  `checks`, so a frontend is made to handle their absence rather than being promised a rate
  this API does not always send.

The constraint is written as two compound branches rather than an `or` chain of equalities,
because the migration README beside it records that H2 folds such a chain into an `in` list and
then rejects every later insert.

## `accounts` gains a second export: a Currency read without a lock

Phase two needs to know both Accounts' Currencies before it can ask *does this Transfer need a
rate at all*, and it has to know before any transaction opens — the alternative is a provider
call inside one, which is the whole thing §4 is arranged to avoid. §30 keeps `AccountRepository`
package-private and exports locking alone, so this is a genuine second export rather than a
convenience.

It is safe for that field and for nothing else on the row: an Account's Currency is fixed when
it is opened and nothing changes it. `AccountCurrenciesAreReadWithoutALockTest` states that as
the precondition rather than leaving it in a comment.

The day it stops being true is caught rather than assumed. `FundsReservation` re-checks, under
the lock, that the amounts phase two resolved are denominated by the Accounts it has just
locked, and throws if they are not. It cannot fire today. What it holds is a redenomination, or
an Account deleted and its ID reused — either of which would otherwise arrive as two amounts
quietly denominated in a Currency neither Account holds, with `Account.reserve` refusing the
debited side and nothing at all questioning the credited one.

## Found by the suite — "every Account a reservation reads is read under a row lock" stopped being true

`TheBalanceCheckHappensUnderTheLockTest` drove `TransferScenario.reserve`, which now runs both
phases, and counted every `from accounts` statement the call issued. Phase two adds two unlocked
ones, so the test went red with three or four reads where it expected two.

**The right repair is not to widen the assertion.** The claim is about phase three and it is
still exactly true of phase three; what changed is that the helper it was written against grew
a phase whose whole purpose is to read without a lock. So the test drives the two phases apart
and clears the statement log between them, and the unlocked reads are held to their own claim in
`AccountCurrenciesAreReadWithoutALockTest` — two rows, neither with `for update`.

Recorded because the tempting repair — asserting that *at least* the locked reads are locked, or
filtering the unlocked ones out by shape — would leave the test green against a future change
that moved a balance read out from under the lock, which is the one defect it exists to catch.

## `executeOnce` gains phase two as a `Supplier` and a `Function`, and the four-argument overload is gone

Ticket 17 wrote the port with phase two left out and named its condition: "ticket 26 is the
first caller that has one". The signature it grew is
`executeOnce(key, hash, responseType, Supplier<R> resolution, Function<R, T> operation)` — the
resolution runs after the key is claimed, so a duplicate never pays for a provider call, and
before the transaction opens, so a slow provider is never something a row lock waits on.

The old four-argument overload was deleted rather than kept, because after this ticket it has no
call site, and an overload that lets a caller skip phase two is an overload that lets the next
caller put an FX fetch inside the transaction without changing anything that looks wrong.

**The resolution runs inside the same `try` that releases the claim**, which is what makes the
ticket's "the key is left `FAILED` after a provider failure" checkbox true rather than
aspirational. It is one line of placement and the entire difference between a retry that
executes and a client permanently answered with a stored `503`.

## `Retry-After: 5` on the `503`, and it is a constant rather than a derivation

Ticket 17 left the number to the ticket that had an outage to advise about. Five seconds,
because the work this refusal asks a client to wait out is somebody else's server rather than
one of our transactions — the `409` for a request still in flight says `1` for exactly that
contrast. A request that reaches this refusal has already spent the whole `payments.fx.*` retry
budget, so a client coming back inside that window would only spend it again against a provider
that has had no time to recover.

**Deliberately not derived from those settings.** This is a client-facing promise about an
outage, and tying it to the client's own timeouts would make retuning a timeout silently retune
the advice given to every caller.

## `numeric(20, 10)`, and rates are compared with `isEqualByComparingTo`

Ten decimal places: the stand-in provider quotes at six, and ten leaves headroom for a finer
real quote without silently rounding it. `@Column(precision = 20, scale = 10)` on the entity
repeats the figure because `ddl-auto=validate` compares the two and fails startup if they
disagree (§29).

The consequence reaches every test that reads a rate back. A rate written as `390` returns as
`390.0000000000`, which is the same rate and a different `BigDecimal`: `isEqualTo` fails and
`isEqualByComparingTo` passes. This is a trap rather than a triviality because the failure
message shows two numbers that look identical.

## Measured — H2 rejects a comma-separated list of `add column` clauses, and it takes the whole suite down

`V6` was first written as one `alter table transfers add column exchange_rate …, add column
exchange_rate_fetched_at …`. H2 fails the statement on the first comma, Flyway fails the
migration, and **every booted test in the suite** then fails on context startup — not just the
ones this ticket touched. What reaches the console is a wall of
`ApplicationContext failure threshold (1) exceeded`, with the actual Flyway error scrolled off
somewhere above it.

It is two statements now, and the trap is recorded in `src/main/resources/db/migration/README.md`
beside the `in (…)` one, which is where the next migration author will be looking.

## `TransferResponse`'s "every member is required" claim became false, and one assertion would have hidden it

`TransferListingTest.describesBothReadEndpointsInTheOpenApiDocument` asserted that the published
schema's `required` list was exactly this record's components. Two nullable members made that
false, and the obvious repair — assert `required` equals the components minus those two — is
weaker than it looks: **a schema that had dropped both members entirely would satisfy it.**

So it asserts the two halves separately. The members are *declared* in the schema, and they are
*not* in `required`. That is the actual contract, and the second half is what makes the
generated client handle an absent rate.

## Observed — the quote's timestamp is *later* than the Transfer's, and three fixtures said otherwise

A real cross-Currency request, run against the application with the stand-in provider serving:

```
"exchangeRate":395.000000,"exchangeRateFetchedAt":"2026-09-06T19:56:38.638668532Z",
"createdAt":"2026-09-06T19:56:38.320253112Z"
```

The quote is 318ms *after* the Transfer. That is correct and it is not what either fixture
assumed. `createdAt` is stamped from the injected `Clock` at the top of the controller method,
before the key is even claimed; the quote comes back some way into phase two. So the intuitive
ordering — a rate is fetched, and then the Transfer it prices is written — is wrong about which
instant `createdAt` holds.

`TransferRows.insertTransfer` was placing the quote two seconds *before* the row's `createdAt`,
`OneTransferByIdTest` was asserting exactly that, and `TransferRequestContractTest`'s
`RATE_FETCHED_AT` was two seconds early with a Javadoc teaching the same wrong ordering as a
rule. All three were green, and all three were teaching a reader an ordering the application
cannot produce. The fixtures now place the quote after, and the assertion reads `isAfter`.

Worth recording because nothing was broken: a placed row is legal either way, the constraint
does not care, and there is no test that could have failed. It took reading an actual response.

## Measured — `doesNotExist()` cannot see the difference between an absent member and a null one

The schema published for `TransferResponse` omits the two rate members from `requiredProperties`,
the generated client types them `exchangeRate?: number`, and both the record's Javadoc and this
document said they were *absent* for a same-Currency Transfer. Two tests asserted it with
`jsonPath("$.exchangeRate").doesNotExist()` and were green.

The wire said otherwise. Asserting on the raw body instead:

```
{"id":31, … ,"creditedAmountCurrency":"EUR","exchangeRate":null,"exchangeRateFetchedAt":null, …}
```

Jackson's default inclusion is `ALWAYS`, so a null member is written out as `null`, and
`JsonPathExpectationsHelper.doesNotExist` passes on a present-but-null member. **Both assertions
were green for the wrong reason, and the contract they were guarding was not the one being
served** — an optional member that is missing is a different promise from one that is present
and null, and a literal `null` does not satisfy `number | undefined`.

The fix is `@JsonInclude(NON_NULL)` **on this record rather than globally**. The global setting
would also change the problem document, where `ValidationError` reports a class-level violation
as a null `field` — a null worth sending. Both tests now assert against the raw body, because
`doesNotExist()` would go on passing if the annotation were deleted, which makes it the wrong
tool for the one claim it was being asked to make.

## Observed — a provider that answers `404` is a `500` here, not the `503`

Also caught by pointing the client at a path the application does not serve, which was a
mistake rather than an experiment. [Ticket 25](25-exchange-rate-client.md) split a deterministic
refusal from an exhausted budget precisely so this would happen: asking again gets the same
answer, so there is nothing for a caller to retry and nothing worth a `Retry-After`. The
`503` needs a `5xx` or a dead connection, which is what `payments.mock-fx.failure-rate=1.0`
produces. The split is now confirmed end to end rather than only at the client's own seam.

## `ScriptedExchangeRates` is a witness, not a stub, which is why it is not a `@MockitoBean`

Three of this ticket's checkboxes are claims about what the provider was *asked*, not about what
it answered: that a same-Currency Transfer never reaches it, that a cross-Currency one asks for
the right pair, and that whoever asks has no transaction open at the time. A mock could be
verified for the first two; the third is a property of the caller's thread at the moment of the
call, and there is nowhere to observe it but inside the provider.

So it is a real bean, substituted by `@Primary` and `@Import`, that records the pairs it was
asked for and whether `TransactionSynchronizationManager.isActualTransactionActive()` was true
when it was. `failTheNext(n)` counts rather than latching, because the claim worth making about
an outage is that the *same* key resubmitted afterwards executes — which needs the provider to
have recovered by the second request.

The stand-in under the `mock-fx` profile was rejected for this: it picks its own rate and fails
at a configured probability, and neither is something a credited figure can be asserted against.

**Every class using it shares one Spring context**, because Spring's context cache keys on the
configuration. That is why the `@Import` sits on a `ScriptedRateScenario` base rather than on
each class: three classes carrying identical annotations happened to share a context, three
classes inheriting them cannot stop sharing one by accident. The saving is worth making
structural because losing it is invisible — a second booted context is slower and not wrong.

The bean is therefore a singleton across them, so the base resets it in a `@BeforeEach`, which
runs before every subclass's own because JUnit takes a superclass's first. Adding a `properties`
attribute to any one subclass still silently doubles the boots; inheritance moves the accident
further away rather than ruling it out. Ticket 18 recorded the same cache behaviour from the
other side, where a second class would have cost a second context.

## `Transfer`'s requesting constructor is now package-private

It takes a `ConvertedAmounts`, which is package-private in `transfers`, so a public constructor
would have been uncallable from outside anyway. Making it match is the honest version:
`FundsReservation` is the only thing that may bring a Transfer into being — a rule
`NothingButTheReservationCreatesATransferTest` already states — and other packages read a
Transfer rather than making one. `CheckLedgerTest` moved onto the protected JPA constructor,
which is what a test placing a row was really doing.

## What went to the README instead

The README carries the two new response members and when they are absent, the `422` for a
conversion that rounds to nothing, the `503` with its `Retry-After` and what a client should do
about it, and the removal of `cross-currency-unsupported` from the problem-type table. Ticket 14
had added that URN as a placeholder for the refusal this ticket exists to delete.

Re-quoting the rate at settlement stays deferred and is in [deferred.md](../deferred.md): §15
locks the rate at request time, and whether a long-pending Transfer should be re-priced is a
decision about who carries FX movement risk between the operator and the bank. Nothing technical
blocks it.
