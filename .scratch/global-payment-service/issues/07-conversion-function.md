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

**Status:** ready-for-agent

- [ ] The function takes only values — no repository, no clock, no Spring
- [ ] Rounding is HALF_EVEN and the scale shift is applied in both directions
- [ ] A result of zero Minor Units is reported as a distinct outcome, not as a zero amount
- [ ] Unit tests cover EUR→HUF, HUF→EUR, USD→EUR, a half-way rounding case and the
      round-to-zero case
