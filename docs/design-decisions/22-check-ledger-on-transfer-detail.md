# Ticket 22 — the Check Ledger on the single-Transfer response

§8's ledger reaches a reader. `GET /api/transfers/{id}` now answers with the Checks the
Transfer requires and how each has been answered, which is the first time anything outside
`transfers.checks` can ask what a Transfer is waiting on. The listing is untouched.

Touches §8 of the [initial decisions](00-initial-decisions.md), which said the ledger *is*
the pending-state UI, and makes that sentence true rather than intended. It contradicts
nothing. What it settled that the section did not reach is **where the ledger goes on the
wire** — and the answer costs something, which the first section is about.

---

## One Transfer type, and the member that is optional because of it

`TransferResponse` gained a tenth member, `checks`, which the single-Transfer endpoint
sends and the listing leaves out. Both endpoints still answer with the same record, so
[ticket 32](32-openapi-type-generation.md) still generates one Transfer type and the
screen a client lands on after submitting is still the same shape it refetches later.

**The cost is real and is paid in the generated types.** `checks` is the only component
left out of `@Schema(requiredProperties = …)`, so `schema.gen.ts` declares it
`checks?: components["schemas"]["CheckResponse"][]` — optional on the very screen
([ticket 41](../../.scratch/global-payment-service/issues/41-transfer-detail-page.md))
that exists to render it. That screen will have to handle a Transfer whose ledger is
absent, a case the endpoint it calls never produces. Nothing in the type system says the
detail endpoint always sends it; only this document and the tests do.

That is the trade, chosen knowingly over two alternatives that each buy the missing
guarantee with something else:

- **A nested envelope** — `TransferDetailResponse(transfer, checks)`, with `checks`
  required. The generated type would then be honest at both endpoints, and the price is
  that a client reads a Transfer out of `response` here and out of the array element
  there, so every consumer of a Transfer starts with an unwrapping step that differs per
  endpoint.
- **A flat detail record** — nine members duplicated with `checks` added, required. Honest
  types again, and now two Transfer-shaped schemas in the document with nine members that
  have to be kept in step by hand. §32's whole point is that the frontend's Transfer
  comes from one place.

**And the listing's published schema moved, even though its bodies did not.** The ticket
asks that "the list endpoint is unchanged", and on the wire it is —
`leavesTheCheckLedgerOutOfTheListing` inserts a real ledger row and holds the listing to
sending no `checks` member at all. But both reads share one schema, so
`GET /api/transfers` now *advertises* an optional `checks` array it will never send. That
is the same trade seen from the other end: the one type is what the frontend generates
from, and a shared type shares its optional members in both directions. The listing sends
absent rather than `[]` precisely because absent is the only one of the two that is not a
claim — an empty array there would say every listed Transfer requires no Checks.

It is also exactly the case [ticket 32](32-openapi-type-generation.md) had in mind when it
rejected a springdoc customizer that marks every property of every schema required: such a
rule would lie the first time a response had a genuinely optional member. This is that
member, and it arrived one ticket after the rule was declined.

The exception is held in place by a test rather than by a comment.
`describesBothReadEndpointsInTheOpenApiDocument` still compares the document's `required`
list against `TransferResponse`'s own record components, now less one name it states as a
constant — so a *second* optional member cannot be added without editing the filter and
meeting this decision on the way past.

## An outstanding Check has no `verdict`, rather than a null one

`CheckResponse.verdict` carries `@JsonInclude(NON_NULL)`, so a Check nobody has answered
is `{"check": "FRAUD"}` and not `{"check": "FRAUD", "verdict": null}`.

The two are different promises rather than two spellings of one. An absent member is one
a generated client is *made* to handle missing; a null member is a value it has to
remember can be null, in a language where the type says it cannot. It is also the shape
the rest of the build already commits to:
[ticket 19](19-check-ledger-and-policy.md) records an unanswered Check as an absent column
rather than as a third `Verdict`, `CheckState` carries that out of the package unchanged,
and this is the same claim one layer further out.

**It is the opposite judgement to the one `OpenApiConfiguration` records for a validation
error's `field`, and the contrast is the argument.** A class-level violation belongs to
the request rather than to any one input, so the member is present and null — there *is* a
field slot, and nothing fills it. A Verdict nobody has given belongs to nothing, so there
is nothing to send.

## `stateOf` reports an empty ledger; `decide` refuses one

`CheckLedger` now exports a third operation, and it is the first that decides nothing.
[Ticket 19](19-check-ledger-and-policy.md) had `LedgerDecision.decide` refuse an empty
ledger rather than settle it, because "nothing outstanding, nothing rejected" is satisfied
by a ledger nobody checked anything in, and the caller is about to move money on the
answer. `stateOf` is handed the same empty ledger and reports it as empty.

Nothing is decided here, and the consequences run the other way: refusing would make the
one screen that could show an operator a Transfer nothing will ever settle the one screen
that will not open. The two answers are consistent because the question differs — *may
this Transfer be settled* has no safe answer over no rows, *what is this Transfer waiting
on* has one.

The return value cannot distinguish "a Transfer with no ledger" from "no such Transfer",
and does not try. The caller has already loaded the Transfer, so it is the half of the
system that can tell them apart, and it does — `TransferLookup.byId` throws
`UnknownTransferException` before it asks.

## `MANDATORY` on a read, for a reason the writes do not have

`openFor` and `record` require an existing transaction because a ledger written or
answered in a transaction of its own would commit separately from the Transfer it belongs
to. `stateOf` writes nothing, so that reasoning does not reach it. The reason it is
`Propagation.MANDATORY` anyway is that **the Transfer and its ledger have to be one
snapshot**: a Verdict committing between the two reads would produce a response describing
a settled Transfer that is still waiting on a Check, in the one place somebody goes to
find out which of those is true.

Stating it to the container rather than honouring it by convention is what makes it a
property of the operation. A future caller that reads the ledger on its way to some other
response fails immediately and visibly, instead of returning a torn answer that is right
almost always — the failure mode `@Transactional`-by-convention leaves behind and no test
would catch.

## `TransferLookup` answers with representations, both methods

Both read methods return `TransferResponse` now. The detail read had to: it assembles two
sources that must be read together, and the transaction that makes them one snapshot is
here. A controller handed the Transfer and asked to fetch the ledger itself would be doing
so after the transaction it needed had closed.

Once one read reports the representation, both do. A class whose two methods hand back
different kinds of thing — an entity here, a response there — is where the next reader
guesses wrong, and the mapping the listing was doing in the controller was the same
`TransferResponse::of` either way. `TransferController`'s two `GET`s are now pure
delegation.

This does mean the mapping moved out of the controller and into a `@Service`, which is the
opposite direction from where a "controllers map, services return domain objects" habit
points. The habit is worth less here than the invariant. It is worth being exact about
which invariant: `Transfer` has no lazy associations — every column is a materialised
basic field — so a detached one maps perfectly well outside the transaction, and nothing
here is about avoiding a lazy-loading failure. What the transaction boundary buys is the
**snapshot**: the second read has to happen inside it, so the assembly that needs both
reads has to happen inside it too.

## One ordered query, serving both paths

`findAllByTransferId` became `findAllByTransferIdOrderByRequiredCheck`, and both the
settlement path and the reporting path use it.

`decide` is specified over the rows in any order and says so, so the ordering is
unnecessary for it — but it is not free to omit for the reader. A Check Ledger that
reshuffles itself between two refetches of unchanged data is a screen an operator cannot
trust, whichever way ticket 41 lays it out. That is the same argument the listing's
`created_at DESC, id DESC` rests on in [ticket 15](15-list-transfers-endpoints.md), one
level down.

**What is promised is stability, not a running order.** `required_check` stores the
constant's name, so the order is alphabetical: `FRAUD` before `MANUAL_APPROVAL` today by
coincidence of spelling rather than because the policy runs fraud screening first, and a
Check named `AML` would sort ahead of both. That is enough for what the ordering is for —
the same rows come back the same way — and claiming more would be claiming something the
column cannot carry. A policy order would need a column of its own, written when the
ledger is opened, and nothing yet renders one.

The rejected shape is two repository methods differing only in an `ORDER BY`: an ordering
the settlement path does not need is cheaper than a second method every future caller has
to choose between, and the wrong choice would be invisible. The Check is a total order on
its own because `check_ledger_one_row_per_check` makes one Check at most one row of a
Transfer's ledger, so there is no tie to break.

## The row reports itself, rather than growing getters

`CheckLedgerEntry` gained one method, `state()`, returning the new public
`CheckState(Check, @Nullable Verdict)`. It did not gain `getRequiredCheck()` and
`getVerdict()`.

The row's three existing predicates (`isFor`, `isRejected`, `isAnswered`) exist so that
no caller ever holds a `Verdict` taken off a row — that is what keeps the settlement rule
in `LedgerDecision` rather than spread across whoever reads the ledger. A pair of
accessors added for the reporting path would be reusable by construction, and the first
thing they would be reused for is the shortcut past those predicates. One method that
hands out both facts at once, to the one caller that has to serialise them, is the smaller
hole.

`CheckState` is public and `CheckLedgerEntry` stays package-private, so reading the ledger
remains a strictly weaker capability than holding it. `CheckState` deliberately carries no
identifier: a ledger row is addressed by the Transfer and the Check that name it, which is
the pair `InternalVerdictController` already answers one at.

## The finding: springdoc publishes no `Check` or `Verdict` schema

`describesOneLedgerRowInTheOpenApiDocument` was written asserting
`$.components.schemas.Check.enum` and `$.components.schemas.Verdict.enum`, on the
assumption that a public enum on a published response becomes a named component. It does
not. Checked against the running application's document: springdoc **inlines every enum it
derives from Java**, at each use, so the constants live at
`components.schemas.CheckResponse.properties.check.enum` and `…properties.verdict.enum`.

`TransferStatus` is inlined the same way and has been all along — twice in this document,
once in `TransferResponse` and once as the listing's query parameter. `ProblemType` is the
only enum with a component of its own, and only because
[ticket 05](05-problem-detail-contract.md)'s `OpenApiConfiguration` builds that schema by
hand.

The test now reads the constants where the document actually publishes them, and still
compares them against `Check.values()` and `Verdict.values()` rather than a written-out
list — [ticket 32](32-openapi-type-generation.md)'s reasoning for `ProblemType`: a list
written out in a test is the second copy of a vocabulary, and the one that goes stale
silently when a Check is added to the policy.

Nothing in `src/main` changed as a result. A named component would have been marginally
nicer for a generated client — `Check` as an exported type rather than a repeated union —
and buying it means a customizer registering the two enums by hand, which is a permanent
piece of configuration bought for a cosmetic difference in a file nobody edits.

## Four smaller things

**`TransferRows.empty` deletes `check_ledger` first.** It could not have been wrong
before, because nothing wrote a ledger row from a test that used this fixture; the moment
one did, the foreign key refused the `transfers` delete and every test sharing the H2
database failed on teardown rather than on its own claim.

**`insertCheck` takes a nullable `Verdict` rather than there being two methods.** The
tests that matter here assert that an outstanding row and an answered one are told apart;
writing them through one call with different arguments keeps that difference in the
argument, instead of moving it into which fixture method the test happened to reach for.

**`OneTransferByIdTest` asserts the ledger cannot be read outside a transaction.** The
`MANDATORY` propagation above is a claim about behaviour, and a claim only a test can
hold: nothing about `stateOf`'s body would fail if the annotation were deleted, and the
response it feeds would still be right in every test that runs one call at a time. It sits
in the endpoint's own class rather than beside `openFor`'s equivalent in
`EveryTransferOpensItsCheckLedgerTest`, because what the propagation protects here is this
response — a torn one — and a reader asking why this endpoint can be trusted should not
have to find the answer in another file.

**An empty ledger is asserted, not only argued.** The test named for it writes a Transfer
with no rows and expects a `200` carrying an empty `checks` array. The state is
one the application cannot produce — the ledger is opened in the Transfer's own transaction
and an ArchUnit rule holds Transfer creation to one place — so the row goes in by hand.
That is the point: it is reachable by a failed migration or an edited database, which is
exactly when somebody needs the screen to open.

## Rejected alternatives

**Rejected — a nested `TransferDetailResponse(transfer, checks)`.** Honest required types,
at the price of two ways to read a Transfer out of a response. See above; this was the
recommendation, and the flat shape was chosen over it deliberately.

**Rejected — a flat detail record duplicating the nine members.** Two Transfer-shaped
schemas kept in step by hand, against §32's one-generated-Transfer premise.

**Rejected — `checks` on the listing too, as an empty array or a populated one.**
Populated, it reads a second table per row to fill a screen that renders none of it.
Empty, the listing would be claiming every Transfer requires no Checks, which no Transfer
does. Absent is the only one of the three that is true.

**Rejected — `verdict: null` for an outstanding Check.** A null a client must remember to
test for, where an absent member is one the generated type makes it handle.

**Rejected — a `status` or `outstanding` boolean beside the Verdict.** Derivable from the
Verdict's absence, and a second discriminator that can disagree with the first — §18's
argument about `type` and a `code` field, at the level of a ledger row.

**Rejected — exposing `CheckLedgerEntry` or its identifier.** The row stays
package-private; a caller that could name a row would be one edit from writing one, and
the identifier addresses nothing a caller can act on.

**Rejected — `stateOf` refusing an empty ledger, symmetrically with `decide`.** The two
answer different questions; only one of them is about to move money.

**Rejected — reading the ledger from `TransferController`.** The transaction is in
`TransferLookup`, so the controller would be assembling a response from two reads that are
not one snapshot, which is the whole thing `MANDATORY` is here to prevent.

## What this ticket deliberately did not build

- **The screen.** [Ticket 41](../../.scratch/global-payment-service/issues/41-transfer-detail-page.md)
  renders this. The generated types are regenerated here, on
  [ticket 21](21-internal-verdict-endpoint.md)'s precedent that a backend ticket moving the
  OpenAPI document refreshes `schema.gen.ts` in the same commit, so that a stale copy can
  never be what a later frontend ticket is written against.
- **When a Check was answered.** The ledger has no timestamp column, so the response
  reports *that* a Check answered and never *when*. This is the first ticket that reads
  the ledger for a person, and therefore the first that could have added one; it did not,
  for the reason recorded in [deferred.md](../deferred.md) — the column is cheap and the
  reader that would shape it does not exist yet.
- **The deadline.** §14's expiry belongs to the Transfer and arrives with
  [ticket 23](../../.scratch/global-payment-service/issues/23-expiry-reaper.md). A
  detail response showing outstanding Checks and no time left to answer them is
  incomplete in a way ticket 23 completes, not in a way this ticket could.
- **Which Check service answered.** Nothing records it —
  [ticket 21](21-internal-verdict-endpoint.md)'s shared secret authenticates no
  particular caller — so the audit trail names Verdicts and not reporters.
