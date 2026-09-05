# Ticket 07 — the cross-currency conversion

§16 stated the formula and the two rules around it — HALF_EVEN, and `422` when
the result rounds to zero. This ticket built it and settled the two things the
formula does not say: what the function returns, and where it lives.

Touches §16 and §30 of the [initial decisions](00-initial-decisions.md).

---

## The round-to-zero outcome is a sealed type, not an empty `Optional`

`ConversionResult` is a sealed interface over `Converted(Money)` and
`RoundsToZero`, so ticket 26's `switch` over it is exhaustive and the compiler
refuses to let the `422` case go unwritten.

**Rejected — `Optional<Money>`.** It is the cheaper shape and it is the wrong
one for this specific value. `Optional.empty()` does not name what happened, and
worse, the mistake the ticket exists to prevent stays a legal one-liner:
`convert(…).orElse(Money.zero(destination))` compiles, reads plausibly, and is
precisely the debit-the-source-credit-nothing bug. A sealed type does not make
that mistake harder to write; it makes it impossible to write by accident,
because there is no zero-money value to fall back to.

**Rejected — throwing.** Spring's habit is to throw and let `@ControllerAdvice`
answer, and here it would put a web concern inside a function whose whole point
is that it is a pure calculation of three values. It also mis-classifies the
event: a conversion rounding to zero is the arithmetic working, and its answer
being that this transfer cannot be made. The ticket's own wording — "reports
that case distinctly", "a distinct outcome" — is a return value, and §16 puts
the `422` at the caller.

**The rule is on the result, not on the ratio.** Any conversion landing on zero
Minor Units reports `RoundsToZero`, including a source amount that was already
zero. That needs no special case and states the invariant the caller depends on:
a `Converted` never carries nothing.

## It lives in `fx`, which now exports two public types

**Adds to §30 without contradicting it.** Its sketch lists `fx/` as
`ExchangeRateProvider (public) · HTTP client package-private`;
`CurrencyConversion` is a second public type there. The sketch is left alone
because it is not now false — it names what the package exports without claiming
to be the whole list — and §30's stronger claim, that the three public *ports*
are §3, §11/§12 and the FX client, is untouched: a pure function of three values
is neither a port nor a substitution seam.

**Rejected — `common`, beside `Money` and `Currency`.** It is where the two
types it operates on live, but `common`'s stated job is the vocabulary *every*
slice shares, and only `transfers` converts. Putting it there would grow the
package everything depends on in order to serve one caller. `fx` already owns
the Exchange Rate, and the conversion is a function of one.

The new edge is `fx → common`, one way, and the ArchUnit cycle rule covers it.

**Deferred — an `ExchangeRate` type.** `destination` and `exchangeRate` travel
together and will travel together again when ticket 26 locks a rate onto a
Transfer, which is the shape of a type wanting to be born, and CONTEXT.md
already names the concept. It is not born here because ticket 25 defines what
the provider actually returns — whether a rate carries its pair, its timestamp
and its validity window — and inventing that shape one ticket early would fix it
against a guess rather than against the port. Ticket 25 or 26 should pull it up
once there is something real to model.

**Not added to `Money`.** Ticket 06 held its surface to four methods on the
grounds that a fifth should arrive with a real call site. This is that call
site, and it still does not belong there: the operation needs a rate, which
`common` has no concept of, so `Money.convertedTo(…)` would drag `BigDecimal`
rates into the type whose entire point is that money is not a decimal.

## A rate of zero was a `422` blaming the wrong party

**Beyond what the ticket asked for, and caused by the shape it asked for.** With
round-to-zero as a reported outcome, a rate of `0` — a provider bug, or an
unparsed field defaulting — produces `RoundsToZero`, which ticket 26 turns into
a `422` telling the operator their transfer was too small. That answer is
confidently wrong about whose fault it is, and nothing downstream could tell.

`convert` therefore refuses a rate at or below zero with
`IllegalArgumentException`, the same way `Money` refuses mixed currencies: a bad
rate is a bug above this function rather than something a user asked for, so it
is an unchecked exception and nothing catches it. Ticket 25 may well validate
at the port too; this is the backstop that keeps the `RoundsToZero` outcome
honest, and it is cheap enough to keep in both places.

## What the arithmetic needed, and did not

**One expression, both directions.** `BigDecimal.movePointRight` takes a
negative argument and moves the point left, so `destScale − srcScale` is applied
as written with no branch on which currency has more decimals.

**Overflow is refused, not wrapped**, via `longValueExact`, matching the
`addExact`/`subtractExact` choice ticket 06 made for the same reason: a wrapped
count of Minor Units is the one way this representation can hold a quantity that
is not the answer.

**§16's table has a same-currency row and these tests do not.** That row is
about ticket 26 making no provider call at all, which is not something a pure
function can demonstrate. The arithmetic it would exercise — a scale shift of
zero — is exactly what the USD→EUR case already covers.

**The rounding test asserts both halves of HALF_EVEN.** A single tie rounding
up agrees with HALF_UP, and a single tie rounding down agrees with HALF_DOWN, so
either alone would pass against a rounding mode the design rejected. Asserting a
tie that goes up *and* one that goes down leaves HALF_EVEN as the only mode that
fits; flipping the implementation to HALF_UP was run once to confirm the test
fails.
