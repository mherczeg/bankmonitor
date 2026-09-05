# 07: The cross-Currency conversion, as a pure function

**What to build:** The single most error-prone calculation in the backend, isolated from
everything that makes it hard to test. Given a source amount in Minor Units, an Exchange
Rate and the two Currencies, produce the destination amount in Minor Units:

```
destMinor = round(srcMinor × rate × 10^(destScale − srcScale))     HALF_EVEN
```

The scale shift is what makes 100 HUF and 100 EUR-cents different quantities, and it is
the term most likely to be dropped. Rounding is HALF_EVEN.

A conversion that rounds down to zero Minor Units is not a valid Transfer — it would
debit the source and credit nothing — so the function reports that case distinctly rather
than returning zero. The caller (ticket 26) turns it into a `422`.

**Known and accepted:** money is *not* conserved across the two Accounts. The rounding
remainder vanishes, because there is no double-entry ledger and no internal account for
it to land in. That is recorded in `docs/deferred.md`; do not invent a home for it here.

**Blocked by:** 06

**Status:** done

- [x] The function takes only values — no repository, no clock, no Spring
- [x] Rounding is HALF_EVEN and the scale shift is applied in both directions
- [x] A result of zero Minor Units is reported as a distinct outcome, not as a zero amount
- [x] Unit tests cover EUR→HUF, HUF→EUR, USD→EUR, a half-way rounding case and the
      round-to-zero case


## Comments

Built as `CurrencyConversion.convert` in `hu.bankmonitor.payments.fx`, returning a sealed
`ConversionResult` of `Converted(Money)` or `RoundsToZero`. Six unit tests, no Spring.

**What it settled, and the alternatives it rejected, are in
[design decision 07](../../../docs/design-decisions/07-conversion-function.md)** — the
return shape, the choice of `fx` over `common`, and why the ticket's own round-to-zero
requirement forced a guard on the Exchange Rate that the checkboxes did not ask for.

Two things worth recording against the ticket rather than the design:

**The half-way case needed two assertions, not one.** A single tie rounding up also
passes under HALF_UP, and a single tie rounding down also passes under HALF_DOWN, so
either alone would have been a test that agreed with a rounding mode the design rejected.
The implementation was flipped to HALF_UP once to watch the test fail rather than assume
it would.

**The both-directions checkbox drove no code.** `BigDecimal.movePointRight` takes a
negative argument, so `destScale − srcScale` applies as written and HUF→EUR passed the
moment it was written. It is kept as a guard on the requirement, not as a red test that
went green.

**Review caught the parameter name.** `rate` is on CONTEXT.md's _Avoid_ list for Exchange
Rate; renamed to `exchangeRate`.
