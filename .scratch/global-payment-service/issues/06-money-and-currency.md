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

**Status:** done

- [x] Money is an embeddable value type pairing a `long` Minor Unit count with a Currency
- [x] Currency is closed to EUR, USD and HUF, each with its decimal places
- [x] Arithmetic on Money rejects operands in differing Currencies
- [x] Unit tests cover equality, arithmetic and the differing-Currency rejection, with no
      Spring context

## Comments

**The surface is four methods — `zero`, `plus`, `minus`, `isLessThan` — and that was the
one decision worth deliberating.** A value type invites a full complement, and
`isNegative`, `isPositive`, `isZero`, `negate` and `times` were each rejected on the same
ground: nothing calls them. Tickets 08, 13 and 26 can add exactly what they use against a
real call site, which is a better shape than one guessed at from a ticket away.

**Rejected — `implements Comparable<Money>`.** It reads as the obvious way to express
ordering and it cannot be honoured: `compareTo` has to be total, so a cross-currency
comparison would either invent an order between euros and forints or throw from a method
whose contract says it does not — and would break anything that sorts `Money` or puts it
in a `TreeMap`. A named `isLessThan` is free to document that it throws. Design decision
16 carries this and the surface above.

**Negative amounts are representable on purpose.** A difference between two amounts is
money, and refusing to hold one here would only push the subtraction out to bare `long`s
where nothing checks the currency. "A balance may not go negative" is the Account's
invariant and ticket 13 enforces it.

**Decision 29 was wrong about the enum mapping, and the correction came from measuring
rather than reasoning.** That decision settled what Hibernate *emits* under `create-drop`
and inferred the rest — including that a `varchar(3)` migration would fail `validate`
under `@Enumerated(STRING)`. It does not. Removing each annotation from `Money` in turn
and running the mapping test gives the full table, which is now in decision 29: either
annotation alone passes and stores the name; only carrying **neither** is dangerous,
because JPA then falls back to **ordinal** and reordering `Currency`'s constants would
silently reinterpret every row already written. `Money` keeps both for division of labour,
not redundancy. Worth the note that the first draft of this ticket's own comments repeated
the decision's inference as fact; the review caught the contradiction with the test's
Javadoc and the experiment settled it.

**`ddl-auto=create-drop` cannot be used with this mapping, which is why the mapping test
is arranged the way the application runs.** Pinning `varchar` makes Hibernate's schema
export add a check constraint that no insert on the throwaway `@DataJpaTest` database can
then satisfy. It is a dead end rather than a problem to solve, because Hibernate emits no
DDL anywhere in this application — decision 29 has the H2 error codes and the evidence it
is the export path specifically. `MoneyMapsToTwoColumnsTest` therefore takes its table
from a migration and runs Hibernate on `validate`, which is the better rehearsal of ticket
08 in any case, and is why the test-only `testsupport/moneymapping` package and
`db/testmigration` directory exist.

**The mapping test is more than the ticket asked for**, which asks only that Money be an
embeddable value type. It is kept because the fourth checkbox's "no Spring context" binds
the equality/arithmetic/rejection tests, and those are Spring-free; this one is an
addition beside them, and it is what turned two inherited claims about the schema into
measurements before ticket 08 builds a migration on top of them.

**Left for ticket 08, and decision 29 was wrong about it.** The implicit naming strategy
does **not** prefix an embeddable's columns with the field name: a field `amount` of type
`Money` produces `minor_units` and `currency`, not `amount_minor_units`. An Account
carrying both a balance and a Reserved Amount therefore needs `@AttributeOverride` or the
two embeddables collide. Note while writing those overrides that `@AttributeOverride`
replaces the component's `@Column` wholesale — which is why `Money` deliberately carries
no `@Column(length = 3)`, a pin that would stop applying at the first override without
saying so. `@JdbcTypeCode` is a separate annotation and is expected to survive, but
nothing here has tested that — confirm it rather than assume it.
