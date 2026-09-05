# 08: The Account, with its balance and what is committed of it

**What to build:** The atomic entity of this context. An Account holds a balance in a
single Currency, fixed at creation and immutable thereafter. Nothing owns it — no `User`
is modelled, deliberately.

It carries **two** balance figures: the balance itself, and the Reserved Amount — the
portion committed to Transfers that have not yet reached a terminal state. Available
Balance is their difference, and it is what an overdraft check tests against. Keeping
both means an Account's own row answers "how much of this is spoken for" without querying
the Transfers.

Ships its own migration (`V1__accounts.sql`). With `validate` on, the entity and the
table must agree on every column name — including the two the Money embeddable generates
for each figure — and a mismatch fails at startup naming the column.

**Blocked by:** 02, 06

**Status:** done

- [x] The accounts table ships as this slice's migration
- [x] The entity carries a balance, a Reserved Amount and an immutable Currency
- [x] The application starts with `validate` on, proving entity and table agree
- [x] A JPA-layer test round-trips an Account and asserts both Money figures map to the
      expected columns

## Comments

**The identifier was the one decision no section had made.** §1–§31 and the issue files
never chose between a `Long` and a UUID, and it goes into a migration that is immutable
once applied. Settled as a database-generated `Long` that is also the public identifier —
`CONTEXT.md` says an Account is "identified by an ID", singular, and there is no account
number in this domain. The honest upside at this size is legibility rather than
performance: the 8-bytes-against-16 and index-locality arguments are real and do not apply
to an in-memory H2 with a handful of rows, whereas §6's ascending-ID lock order is
*debuggable* on a number, and account IDs stay visually distinct from the Idempotency
Keys, which §5 already fixes as UUIDs. Enumerability costs nothing while §1 models no
owner, §2 stubs auth and ticket 10 hands every account to any caller. An internal key plus
a separate public account number was rejected as a boundary this context has no second
side to. Design decision 08 has the full reasoning.

**§1 said `Account` is a Java `record`, and it is false in an interesting way.** Not that
Hibernate refuses it — it *accepts* it. A record entity compiles, boots, and passes
`ddl-auto=validate` against the real migration, matching all four columns; it fails only
at the first `persist`, with `IllegalAccessException: Can not set final java.lang.Long
field Account.id` out of `SetterFieldImpl.set`. Every startup gate this project owns,
including the one `validate` was turned on for, passes a mapping that cannot write a row.
The false sentence is corrected in §1 in place and the measurement is in design decision
08. No test was kept for it: the class shape is visible in the file, and what is worth
keeping is the table of gates, not an assertion.

**The `@AttributeOverride` question ticket 06 left open is answered: `@JdbcTypeCode`
survives — but only after the review caught the first answer being an inference.** The
first draft argued that no separate experiment was needed, since the migration declares
`varchar(3)` and the context starts, "which it could not if the override had dropped the
type pin, because `validate` would then expect a `tinyint`". Ticket 06's own measured
table refutes that: `tinyint` is the **neither**-annotation row, and `@Enumerated(STRING)`
alone passes against a `varchar` quite happily. A booting context proves only that *at
least one* annotation survived. This is precisely the mistake ticket 06 wrote "confirm it
rather than assume it" to prevent, and it took the same remedy — removing each annotation
from `Money` in turn and running this ticket's test, whose columns reach `Money` through
two overrides. With `@JdbcTypeCode` as the only annotation, `validate` still expects a
`varchar` and the name is stored; with neither, it fails. The full table is in design
decision 08, and it is **identical to ticket 06's**, which is the actual finding:
`@AttributeOverride` replaces the `@Column` and leaves the rest of the component's mapping
alone.

That experiment also paid off the ticket's other unproven claim — that a mismatch "fails
at startup naming the column". Only the missing-*table* case had been shown before, by
`FlywayOwnsTheSchemaTest`. The neither-annotation row names it: `wrong column type
encountered in column [balance_currency] in table [accounts]`.

The four names (`balance_minor_units`, `balance_currency`, `reserved_amount_minor_units`,
`reserved_amount_currency`) are the overrides' own; without them both `Money` figures ask
for `minor_units` and `currency` and collide.

**The migration carries two named check constraints, which is more than the ticket asked
for.** `accounts_one_currency` and `accounts_reserved_within_balance`. Both are about rows
the *entity* cannot produce — its constructor takes one `Money` and derives the Reserved
Amount's currency from it — so they cover seed data and anything reaching the table in
SQL. This deliberately does not move the overdraft check out of ticket 13, which still has
to happen in the domain under the lock to reach the caller as a `422`; the constraint only
makes a path around it a failed write. Worth recording that hand-written constraints are
fine under `validate` — the `create-drop` trouble decision 29 documents is the schema
*export* path, which this application never uses.

**Left deliberately undone.** No repository: ticket 12 owns the locking query and 09 the
write path, and a repository written now would guess at both signatures — the tests here
use `TestEntityManager`. No `reserve`/`release`/`settle`: ticket 13 owns those against a
real call site, on ticket 06's principle. `getAvailableBalance()` and `getCurrency()` *are*
here despite that principle, because neither is speculative: the Available Balance is the
whole reason §13 stores two figures rather than one, and the currency is the invariant the
two are constructed to share. Both are asserted.

**The third checkbox needed no new test.** `Account` sits in the application's own package,
so every `@SpringBootTest` in the suite now boots it against `V1__accounts.sql` with
`validate` on. The first red of this ticket was that same gate failing with
`Schema validation: missing table [accounts]` before the migration was written.
