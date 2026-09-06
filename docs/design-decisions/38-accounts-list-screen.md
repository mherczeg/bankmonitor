# Ticket 38 — five figures in a row, and the focus a headless browser will not give you

§21's route table names `/accounts` and §23 named the whole element vocabulary a screen
here is allowed to be built from, so the layout was largely a matter of reading those two
sections back. What building it settled is what a *row* is made of — which figures it
carries, and how many times it says the word EUR — plus one thing neither section could
have known: **a headless browser will not give a spec the focus event this screen's
liveness compensation depends on**, and the way around it is not the one the automation
API suggests.

It is also the first screen that ships, so three patterns that had lived only in a module's
documentation or in test scaffolding become production code: a request function that throws
the parsed problem body, a query filed under `queryKeys`, and `problemToMessage` deciding
what an operator is offered.

Touches §17, §21, §23 and §24 of the [initial decisions](00-initial-decisions.md), and
builds on [ticket 33](33-money-format-module.md)'s formatter,
[ticket 34](34-problem-document-module.md)'s message table and
[ticket 37](37-playwright-harness.md)'s harness, which it adds a helper to.

---

## A table, not cards

§23 lists what this app renders with — "a `<select>`, a form, a table, a badge and a
spinner" — and this screen is what the third of those is for. Five aligned figures per row,
across a handful of Accounts, is exactly the shape a table is good at.

**Rejected — a card per Account.** It is the reflex layout for a list of entities, and it
puts the same five values in five different places on the screen: comparing two Accounts'
Available Balances becomes a scroll and an act of memory rather than reading down a column.
The one thing cards genuinely buy — surviving a narrow viewport without a horizontal
scrollbar — is bought here by Bootstrap's `.table-responsive`, which is a wrapper around the
same markup rather than a second layout to keep in step.

The amount columns are right-aligned and carry `font-variant-numeric: tabular-nums`, which
is the only rule in `accounts.module.css`. A proportional digit set gives `1` a narrower
advance than `8`, so a column of amounts stops lining up digit under digit — which is the
whole of what choosing a table bought.

## The Currency is a column, not a suffix on every amount

An Account is denominated once. CONTEXT.md says so — *"Fixed at Account creation and
immutable thereafter"* — and the API is shaped to match: one `currency` beside three
`…MinorUnits` figures, not three currencies beside three figures. Naming it three times per
row would repeat, on every row, a fact the domain states once, and would do it in precisely
the three cells the eye is trying to compare.

**Rejected — `formatAmount` growing a symbol, or a currency argument that reaches its
output.** [Ticket 33](33-money-format-module.md) settled this before any screen existed: the
formatted string is a form value first and a display value second, it has to read back
through `parseAmount` byte for byte, and *"the screens carry the Currency themselves … so
the symbol was never this function's to add"*. This screen is the first of the screens that
sentence was promising something about, and it carries the Currency in a column.

The Currency is not decoration on the way out either, which is why the browser spec lists a
HUF Account beside a EUR one rather than two of the same. HUF has no decimal places, so a
screen that divided by a hundred whatever it was handed reads perfectly on the EUR row and
is wrong by a factor of a hundred on the other — the one rendering bug this column exists
to make visible.

## The Reserved Amount is shown, though the acceptance criteria name two figures

The ticket's checkboxes ask for the balance and the Available Balance. Its prose asks for
something the checkboxes do not spell out: that an operator "can see how much of an Account
is already committed to in-flight Transfers" — which, by CONTEXT.md's definition, is the
Reserved Amount and nothing else.

It also closes a reading problem the two-column version has. Balance and Available Balance
differ by an amount the screen would otherwise never account for: the gap is visible, its
cause is not, and the operator's next question — *why can I not send all of it* — has no
answer anywhere on the page. The third column makes the subtraction legible without the
screen performing it, which is the next decision.

## The Available Balance is rendered, never computed

Every cell holds a figure the API sent. This screen in particular does not compute
`balance − reserved`, and [ticket 10](10-list-accounts-and-seed.md) is why it does not: the
Available Balance goes on the wire "because it is the figure ticket 13's overdraft check
tests against — a client computing it would be a second implementation of the rule, and the
one that drifts". A subtraction here would look right in every screenshot and be wrong on
the first day the rule grows a term the client cannot see.

**The test fixture derives it on purpose, and that is a different thing.** `anAccount`
computes `availableBalanceMinorUnits` from the balance and the reservation it was handed, so
a spec cannot casually script an Account the backend could never produce. Deriving in a
fixture *constrains what a spec may claim*; deriving in the screen would *replace what the
server said*. The two point in opposite directions, and a spec that genuinely wants the
impossible Account — to prove a rendering survives one — says so by overriding that member
by name.

## `refetchOnWindowFocus` is declared rather than inherited, and the absent `staleTime` is the load-bearing half

`refetchOnWindowFocus: true` is TanStack Query v5's default, so the line added to
`queryClient.ts` changes no behaviour today. It is there because this is not a default the
app is content to inherit — it is a **designed property**. §17 scoped liveness to the
transfer submit flow and said Accounts and Transactions "refetch on navigation and on window
focus" instead; §21 says the default "quietly closes the scope hole §17 left on the other
two screens". A property two sections of the design lean on should not be able to move
because a minor version changed its mind, and someone grepping for it should find it stated
rather than absent.

**The half that is actually load-bearing is that there is no `staleTime`.** A focus refetches
only what is already stale, so any non-zero `staleTime` would silently defeat the feature
while leaving the declaration above it looking correct — the screen would go on showing the
balances it first loaded, and nothing on the page or in the config would look wrong. It is
exactly the value somebody adds to "reduce chatter", so its absence is stated in the config
rather than merely observed.

**Rejected — setting the option on the Accounts query instead of on the client.** Ticket 43's
Transactions screen is compensated the same way for the same reason, and a per-query spelling
would be one decision written twice, in two files, of which only one would be updated.

## The failure is thrown as the parsed body, not wrapped in an `Error`

`listAccounts` reads the response body and, on a refusal, throws that body as it parsed it.
The reflex — `throw new Error(problem.detail)` — would hide the two members everything
downstream reads: `problem.ts` branches on the `type` URN to choose what the operator is
told, and `retry.ts` reads `status` to decide whether the query client asks again. Both would
be handed a `message` and no way back to the document.

The pattern is not new here, but this is where it stops being scaffolding: the four lines are
the ones [ticket 37](37-playwright-harness.md)'s smoke subject carried — written there so the
harness would prove them — and this ticket promotes them into `src/api/accounts.ts`, where a
screen depends on them. `accounts.test.ts` pins it from both sides: the parsed document comes
back out of the `catch`, and what was thrown is **not** an `instanceof Error`, which is the
assertion the well-meaning refactor fails.

`listAccounts` also imports nothing but `./types`, so ticket 33's plain-module assertions
apply to it unchanged.

## The retry affordance is driven by `retryable`, not by the retry rule

The error state renders `problemToMessage(failure)`'s `title` and `body`, and offers a *Try
again* button only when its `retryable` is true. [Ticket 34](34-problem-document-module.md)
drew the line these two answers sit on: `retryable` answers *would trying again ever help*,
which is a question for the person reading the screen, while `retry.ts` answers *should this
be re-sent now, silently*, which is a question about the client's own behaviour. They
deliberately disagree.

The consequence on this screen is worth stating, because it looks like a bug in a diff: **a
4xx the client will never re-send still gets a button.** `retry.ts` re-sends only a `5xx`, so
every failure this screen can show is one the client has already stopped asking about — and
a `urn:problem:client-error` `400` is still worth one more press, while the in-progress `409`
is worth one in a minute. Wiring the button to the retry rule would take it away in exactly
the case where a person can do something the client will not.

The spec pins both ends of that: the `400` renders a button, a press clears the screen and
the endpoint has then been asked twice; a `not-found` `404` renders no button at all. It also
pins that the first failure was one request rather than three — the query client does not
quietly spend an operator's retries on an answer the server has already given.

The button calls `refetch()`, so a press is one more attempt through the same rule rather
than a second request path with its own idea of when to give up.

## Making a real focus happen in a headless browser

This is the finding that cost a spike, and it is visible from neither library's
documentation.

The obvious way to write "the operator came back to the tab" is to open a second page and
then bring the first one back to the front. It does nothing. **Chromium removed
`Emulation.setPageVisibilityState`**, so nothing in the automation API sets that state any
more, and a headless page reports `visible` however many other pages are put in front of it:
`newPage()` + `bringToFront()` fires no `visibilitychange`, the focus manager never runs, the
refetch never happens, and the spec fails on an assertion that names the screen rather than
the mechanism.

So `e2e/harness/focus.ts` does two things, in an order that matters:

1. **Redefine `document.visibilityState`** with `Object.defineProperty`, so the page tells
   the truth about a state the spec chose.
2. **Dispatch `visibilitychange` on `window`** — not on `document`. That is where TanStack
   Query's focus manager registers its listener, and a `document` dispatch reaches nothing.

The state has to move *before* the dispatch, because `isFocused()` reads
`document.visibilityState` at the moment the event arrives; that read is the manager's only
state input, so an event dispatched first is answered with the state the page is leaving.

**Rejected — dispatching the bare event and leaving the state alone.** It passes, and it
passes for the wrong reason: the page really is `visible`, so the manager agrees and
refetches. What the spec then asserts is a state change that never happened. It also cannot
express the other half of the feature — a tab that went away and *stayed away* must not
refetch — because there is no away to be in. `setVisibility` is exported alongside `refocus`
precisely so a spec can drive that negative.

`refocus` moves through `hidden` and back to `visible` for the same reason: the manager only
notices a return if it saw the leaving, since `visible → visible` moves nothing it reads. It
does not dedupe either, which the spec relies on — it leaves and returns twice and asserts
two fresh answers, not one and then a screen that has decided it is already up to date.

**Known limit, recorded in the helper itself:** the override is installed by `page.evaluate`
and so does not survive a navigation. Every call reinstalls it, which makes it a problem for
exactly one shape of spec — one that navigates between the hidden half and the visible half,
and would find the page `visible` again on arrival and run a sequence other than the one it
wrote.

The helper lives in `e2e/harness/` rather than beside the Accounts spec because
[ticket 43](../../.scratch/global-payment-service/issues/43-transactions-list.md)'s
Transactions screen is compensated by refetch-on-focus for the same reason, and will want the
same two calls.

## The loading state is rendered and not asserted in a browser

The screen renders all three states; the browser spec covers two of them. There is no
deterministic moment at which the spinner exists:
[ticket 37](37-playwright-harness.md)'s harness answers every scripted request immediately
and offers no way to hold one open, so an assertion on the spinner would be a race against a
local `fulfill` — the kind of test that is green on a quiet machine and flakes on a loaded
one.

Adding a delay hook to the harness for this one assertion was considered and rejected: it
puts a waiting mechanism into a seam whose central design property is that no spec waits on a
clock, and it would exist for one element. **It is still a real gap** — the loading branch is
the one branch of this screen no browser ever renders — so it is written into
[deferred.md](../deferred.md) rather than left as something that merely did not get done.

## Rows link nowhere

A row is markup, not a link. There is no `GET /api/accounts/{id}` — the endpoint and the
`Location` header that would name it are [deferred](../deferred.md) together, on the argument
that a link to a resource that does not exist is worse than no link — so there is no page for
a row to open. When that endpoint arrives, the row is where the link goes; until then the
absence is the honest rendering.

Nor does the screen sort or filter. The endpoint answers in insertion order, oldest Account
first, and [ticket 10](10-list-accounts-and-seed.md) made that part of its contract, so the
screen keeps the order it was given rather than imposing a second one on top.
[Deferred](../deferred.md), with the reasoning.

## What this ticket corrected, and what it left standing

**No section of `00-initial-decisions.md` was found false.** §17's liveness scope, §21's
route table and its note about `refetchOnWindowFocus`, §23's element list and §24's testing
rule all held exactly as written, and the only edits to that file are pointers to this one.

Two earlier ticket records are *extended* rather than corrected:

- [Ticket 33](33-money-format-module.md) predicted that "the screens carry the Currency
  themselves". This is the first screen, and it does that with a column rather than with an
  adornment on a field — so both of the two shapes that sentence left open now have a
  screen speaking for them, the adornment being ticket 40's.
- [Ticket 37](37-playwright-harness.md) shipped `api.transfer`, `api.transfers` and the
  `aTransfer` fixture with no spec exercising them, and named this ticket as the first to run
  the harness for real. It is: `api.accounts`, `api.refuses` and `anAccount` now drive a
  screen rather than a subject page, and the harness gains `refocus` / `setVisibility`.

The smoke subject page from ticket 37 stays. Its reopening condition is ticket 42 carrying
the same event sequence on a real screen, not this ticket's list existing.
