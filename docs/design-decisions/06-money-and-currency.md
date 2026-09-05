# Ticket 06 — money and currency

§16 chose a `long` count of minor units in a `record`; this ticket built it, and
in doing so measured the JPA mapping §29 had only inferred.

Touches §16 and §29 of the [initial decisions](00-initial-decisions.md).

---

## `Money` as built

`record Money(long minorUnits, Currency currency)` and `enum Currency` in
`common`, with **four methods and no more**: `zero`, `plus`, `minus`,
`isLessThan`. Each of the three that takes an operand rejects a differing
currency with an `IllegalArgumentException` naming both.

**The surface is deliberately smaller than the type could support.** A value
type invites a full complement — `isNegative`, `isPositive`, `isZero`,
`negate`, `times` — and every one of them was rejected on the same ground:
nothing calls it. The tickets that need more (08's balances, 13's overdraft
check, 26's conversion) can add exactly what they use, against a real call
site, rather than this ticket guessing at the shape from one ticket away.

**Rejected — `implements Comparable<Money>`.** It reads as the obvious way to
express ordering, and it cannot be honoured: `compareTo` has to be total, so a
`Money` comparison across currencies would either have to invent an order
between euros and forints or throw from a method whose contract says it does
not. Throwing also silently breaks anything that sorts or puts `Money` in a
`TreeMap`. A named `isLessThan` that throws is a method whose documentation is
free to say so.

**Negative amounts are representable, on purpose.** A difference between two
amounts is money, and refusing to hold one here would push the subtraction out
to bare `long`s where nothing checks the currency. "A balance may not go
negative" is the *Account's* invariant, and §13's reservation is where it is
enforced.

**Overflow throws rather than wraps** — `Math.addExact` / `Math.subtractExact`.
Java's `+` wrapping silently is the one way a count of Minor Units can
represent a quantity that is not the answer, which is the property the whole
decision was chosen for.

---

## The mapping: two corrections to §29

### The column names are the component's own

**§29 assumed the implicit naming strategy prefixes an embeddable's columns with
the field name** — a `Money` field `amount` becoming `amount_minor_units` /
`amount_currency`. It does not. The component's own names are used unchanged:
`minor_units` and `currency`, asserted by
`MoneyMapsToTwoColumnsTest`. An `Account` holding both a balance and a Reserved
Amount therefore needs `@AttributeOverride` or the two embeddables collide on
the same two columns, which is ticket 08's first job. Note while writing those
overrides that `@AttributeOverride` replaces the component's `@Column`
wholesale — which is why `Money` deliberately carries **no** `@Column(length = 3)`,
a pin that would silently stop applying at the first override. `@JdbcTypeCode` is
a separate annotation and is expected to survive an override; nothing here has
tested that yet, so ticket 08 should confirm it rather than assume it.

### Which annotation is load-bearing, measured rather than inferred

**The whole table is not what §29 assumed.**
[Ticket 01](01-project-skeleton.md)'s spike settled what Hibernate *emits* under
`create-drop`, and §29 inferred the rest. Removing each annotation from `Money`
in turn and running `MoneyMapsToTwoColumnsTest` — table from a migration declaring
`currency varchar(3)`, Hibernate on `validate` — gives:

| on the component | `validate` | stored |
|---|---|---|
| nothing | **fails**: expects `tinyint`, found `character varying` | — |
| `@Enumerated(STRING)` alone | passes | `'HUF'` |
| `@JdbcTypeCode(VARCHAR)` alone | passes | `'HUF'` |
| both | passes | `'HUF'` |

Two corrections follow. **The sentence §29 used to carry — that a `varchar(3)`
migration therefore fails `validate` under `@Enumerated(STRING)` — is false**,
and has been removed from it: the native-enum finding is about emitted
DDL, and `validate` compares type *categories*, so it accepts a `varchar` for a
`STRING` enum. And **the two annotations are not each covering a different
default; either alone would do.** What is genuinely dangerous is carrying
*neither*, because JPA then stores the enum by **ordinal** and reordering
`Currency`'s constants silently reinterprets every row already written.

Both stay anyway, and the reason is division of labour rather than redundancy:
`@Enumerated(STRING)` states the intent JPA reads, `@JdbcTypeCode(VARCHAR)` pins
the SQL type for the Postgres move — the one that stops mattering the moment
anything generates DDL. `MoneyMapsToTwoColumnsTest` pins the property that
actually protects the data: strip both and the context fails to start.

**Consequence: `ddl-auto=create-drop` cannot be used with this mapping, and does
not need to be.** Pinning `varchar` makes Hibernate's schema export add
`check (currency in ('EUR','USD','HUF'))`. Against the throwaway
`jdbc:h2:mem:<uuid>` database `@DataJpaTest` substitutes, *every* insert into
that table then fails with H2's `Check constraint invalid: "CONSTRAINT_C: "`
(23514), whose root cause is `The database has been closed` (90098). It is the
schema-export path specifically — the identical DDL run through the test's own
connection accepts the same insert in the same session, and plain H2 outside
Spring accepts it too. Rather than a problem to solve this is a dead end, because
**Hibernate emits no DDL anywhere in this application**: `validate` is the
setting, and the way out is the arrangement the application already uses.
`MoneyMapsToTwoColumnsTest` therefore takes its table from a migration and runs
Hibernate on `validate`, which is the more faithful rehearsal of ticket 08
regardless, and is why a one-entity `testsupport/moneymapping` package and a
test-only migration directory exist. `RecordAsEmbeddableSpikeTest` still uses
`create-drop` and is unaffected: its currency column is a native H2 enum, which
carries no check constraint.
