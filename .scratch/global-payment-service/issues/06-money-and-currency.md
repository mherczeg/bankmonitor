# 06: Money as a count of Minor Units

**What to build:** The value type every amount in this system is expressed in. Money is
an amount paired with the Currency it is denominated in — a number without a Currency is
not Money — and the amount is a whole count of Minor Units held as a `long`.

`BigDecimal` was rejected, and not for drift: it is unconstrained (nothing stops a
fraction of a Minor Unit from being representable) and its equality compares scale, so
two equal amounts can compare unequal. The Exchange Rate stays a decimal, because it is
the one value in this domain that genuinely is one — and it is never Money.

Currency is the closed set EUR, USD and HUF, each carrying the number of decimal places
it is written with (2, 2 and 0). **Those decimals live only at the edges** — form parsing
and display. The core never divides by 100.

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] Money is an embeddable value type pairing a `long` Minor Unit count with a Currency
- [ ] Currency is closed to EUR, USD and HUF, each with its decimal places
- [ ] Arithmetic on Money rejects operands in differing Currencies
- [ ] Unit tests cover equality, arithmetic and the differing-Currency rejection, with no
      Spring context
