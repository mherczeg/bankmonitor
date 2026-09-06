# Ticket 33 — the frontend edge where Minor Units become decimals

§16 said per-Currency decimals "live only at the edges — form parsing and display",
and §24 named `money.ts` as the first module to extract. This ticket built that edge.
The interesting decisions were all about **what the formatted string is for**, because
the ticket asks for two functions that must be inverses and the usual money formatter
is not invertible.

Touches §16 and §24 of the [initial decisions](00-initial-decisions.md).

---

## The formatted string is a form value first and a display value second

`formatAmount(10050, 'EUR')` is `"100.50"` — no symbol, no thousands separator, no
locale. That is the whole of the decision and it follows from the ticket's fourth
requirement: format → parse must be lossless.

**Rejected — `Intl.NumberFormat(locale, { style: 'currency' })`.** The reflex answer,
and it is the right one in an app that only displays money. This one also *reads*
money: ticket 39's create-Account form and ticket 40's transfer form both put an
amount into a field an operator then edits. `Intl` renders 10050 HUF three ways
depending on a locale the app never chose:

| | `HUF` 10050 | `EUR` 100.50 |
|---|---|---|
| `hu-HU` | `10 050 Ft` | `100,50 EUR` |
| `en-US` | `HUF 10,050` | `€100.50` |
| `de-DE` | `10.050 HUF` | `100,50 €` |

`parseAmount` would have to strip a currency symbol and a group separator, and the
last row is why that cannot be done safely: **`10.050` is ten thousand forints under
`de-DE` and a tenth of a euro under `en-US`.** The formatter would be undoing its own
work, and getting it wrong misreads an amount by a factor of a thousand.

So the module renders the one form that is unambiguous in every locale and reads back
byte-for-byte. The screens carry the Currency themselves — ticket 38 lists Accounts
with the Currency they are held in, and ticket 40 shows it as an adornment on the
amount field, which §22 settled for an unrelated reason — so the symbol was never this
function's to add.

**Consequence, accepted:** a large HUF balance renders as `10050`, not `10 050`. Group
separators are a readability nicety on one screen; losslessness is a correctness
property on two. If the Accounts list ever wants grouping, the honest shape is a
second function beside this one, not a flag on this one.

## Neither direction divides by a hundred

Both functions work on the digits of the string. Formatting pads the count to at least
`places + 1` digits and cuts a decimal point into it; parsing pads the typed fraction
out to `places` digits and concatenates.

**Rejected — `(minorUnits / 100).toFixed(2)` and `Math.round(Number(input) * 100)`.**
This is the one module in the frontend allowed to know about the hundred, so it is
also the one place where reaching for arithmetic reintroduces exactly what §16's
`long` count exists to keep out. `Number('100.50') * 100` is `10049.999999999998`, and
the `Math.round` that rescues it is a rounding step in a module whose contract is that
it does not round. The string form has no such step to get wrong: it is not that the
float version is hard to make correct, it is that the correct float version still
leaves a rounding rule in the code for someone to change.

## Rejection is a reason code, not a message and not an exception

`parseAmount` returns `{ ok: true, minorUnits }` or `{ ok: false, reason }`, over four
reasons: `not-a-number`, `too-many-decimals`, `not-positive`, `too-large`.

**Rejected — returning `null` or `NaN` for bad input.** The four reasons are four
different things to tell an operator, and a single failure value throws that away at
the exact moment the form needs it. Tickets 39 and 40 both have to say *why* a field
is refused.

**Rejected — throwing.** The caller is a zod validator running on every keystroke;
half-typed input is the normal case, not the exceptional one.

**Rejected — returning the message.** Tempting, because the wording needs the
Currency's decimal count and this module owns it. But that is UI copy, and putting it
here means the module that must import nothing from React is the module that decides
how errors read. `decimalPlacesIn` is exported instead, so a form can write its own
sentence from the same source of truth.

**The checks run in the order the string is taken apart** — shape, then scale, then
value — so `100.505` in EUR is *too many decimals* rather than *not a number*. This is
not only a nicety: the scale check has to precede the conversion, because padding a
three-digit fraction out to two places would silently truncate it into a valid-looking
amount.

**Two of the ticket's checkboxes cannot both hold, and the resolution is worth naming.**
Round-tripping must be lossless "for every supported Currency"; parsing must reject
"negatives and zero". So `formatAmount(0, 'EUR')` is `'0.00'` and reading it back is a
refusal. Format's domain is every count the API can report; parse's is the sums an
operator can submit, which is strictly narrower. The round-trip property is therefore
over positive counts, which is where it is needed — the only round trip that happens
in the app is a form field being pre-filled and submitted.

## `too-large` exists because a JavaScript number is not a `long`

§16 holds Minor Units in a `long`. The frontend's `number` is a double, exact only to
2^53 − 1, so there is a range the backend can hold and the browser cannot count.
`parseAmount` refuses anything above `Number.MAX_SAFE_INTEGER` rather than submitting
a figure that arrived at the server as a different one.

**The read direction is deliberately unguarded.** `formatAmount` takes whatever the
API sent, and a balance past 2^53 would already have been mangled by `JSON.parse`
before this module saw it — a guard here would be theatre at the wrong end of the
pipe. The bound this module can enforce is the one on input, and that is the one it
enforces. Nothing in this service can reach either bound: 2^53 Minor Units is nine
quadrillion forints, or ninety trillion euros.

`formatAmount` also does not guard against a count outside its contract, and review
was right that the ticket never said what happens then. It is worth saying, because
the answer is a deliberate one: `formatAmount(100.5, 'EUR')` is `'100..5'`, and a
count past 1e21 stringifies to exponent form and comes out as `'1e+.21'`.

Both are unmistakable garbage, and that is the property being kept. The alternatives
are a throw, which turns a caller's bug into a blank screen, or `Math.round`, which
turns `100.5` into a confident `'1.01'` — a plausible wrong amount on a payments
screen, which is the worst of the three outcomes. The contract is in the doc comment;
breaking it produces something no one will mistake for money.

## `CURRENCIES` is derived from the decimals table, not written beside it

The `Currency` type is generated and therefore exists only at compile time, so the
runtime list a `<select>` needs (ticket 39) has to be written somewhere. Deriving it
from `Object.keys` of the decimals table makes it impossible to offer a Currency the
formatter has no decimals for — the failure a second hand-written list would have.

The table itself is a `Record<Currency, number>`, so a fourth Currency added on the
backend arrives here as a type error on the next `npm run api-types`, which is the
same property ticket 32 built the generated types for.

Review read it as written ahead of its caller. It has one already: the ticket's fourth
requirement is round-tripping "for every supported Currency", and a test cannot loop
over a compile-time union.

## No `Money` type on this side, and the vocabulary says there should be

CONTEXT.md is unambiguous — *"a number without a Currency is not Money"* — and the
backend has a `Money` record enforcing it. Every function here instead takes the count
and the Currency as two parameters, and `parseAmount` hands back a bare number. Review
named it as the one place the frontend contradicts the shared vocabulary, and it is.

**Not fixed here, on purpose.** The API sends the two as separate members —
`balanceMinorUnits` beside `currency` on an Account, and §22 settled that a transfer
request carries no Currency at all because the backend derives it from the source
Account. A frontend `Money` would therefore be constructed and destructured at every
boundary rather than received, and it would be a hand-written domain type in the half
of the codebase whose types are generated so that no such thing has to be trusted.

**When to reconsider:** ticket 43 renders Transfers with a source and a destination
Currency in one row, which is the first screen where a loose amount could be paired
with the wrong Currency. If that pairing is awkward there, the type has earned itself
and the place to introduce it is beside these functions.

## The "no React" requirement is asserted, not trusted

`money.test.ts` imports its own subject's source with Vite's `?raw` and asserts the
module's import list is exactly `['./api/types']`.

It is an unusual test and it earns its place: §24's rule is a *rule*, and the way it
erodes is one module at a time reaching for a hook and moving a case like
`too-many-decimals` out of a 2 ms unit test and into a component test that has to
simulate a DOM to reach it. `?raw` rather than `node:fs` because `tsconfig.app.json`
deliberately keeps Node's types out of application code, and a Vite-native import
needs nothing added to it.

The first version of it pinned only `from '…'` specifiers, which review pointed out
is blind to the three ways React could still arrive: a side-effect `import 'react'`,
a dynamic `import('react')`, and double quotes, which nothing in this repo forbids. It
now pins the import list against any of those spellings *and* asserts the word does
not appear in the file at all — crude, and the crudeness is what makes it total.

## The sign in `formatAmount` is not a feature

Both reviews flagged `const sign = minorUnits < 0 ? '-' : ''` as generality no caller
needs, and no caller does need it: `parseAmount` refuses negatives, and every amount
the API reports is non-negative.

It stays because it is not a feature, it is the other half of the `Math.abs` the digit
padding requires. Without `Math.abs`, `String(-5).padStart(3, '0')` is `'0-5'` and a
negative renders as `'0.-5'`; with `Math.abs` and no sign, `-5` renders as `'0.05'` and
a debit is silently shown as a credit. One ternary buys out both, and the second of
them is the kind of wrong this whole module exists to prevent.
