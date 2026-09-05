# Ticket 08 — the Account entity

The first table, and the first entity `validate` has anything to check. §13's two
figures as built, the identifier §1–§31 never chose, and the answer to the
question [ticket 06](06-money-and-currency.md) left open about
`@AttributeOverride`.

Touches §1, §13 and §29 of the [initial decisions](00-initial-decisions.md).

---

## The identifier, which no section had chosen

**A database-generated `Long`, and it is the public identifier as well as the
primary key.** Nothing in §1–§31 or the issue files pinned this, and it goes into
a migration that is immutable once applied, so it is recorded here rather than
inferred from the code.

`CONTEXT.md` says an Account is "identified by an ID", singular, and there is no
account number in the domain: the same value is what `POST /api/accounts` returns,
what `fromAccountId` and `toAccountId` reference, and what ticket 38's list screen
and ticket 40's form select by.

The upside over a UUID at this size is **legibility, not performance**. The
scale arguments — 8 bytes against 16, monotonic B-tree inserts against randomly
scattered ones — are real and do not apply to an in-memory H2 holding a handful
of rows. What a number does buy:

- §6 locks accounts in ascending ID order. *Any* total order satisfies that
  deadlock argument, UUIDs included; what a numeric one gives is a debuggable
  order — "locked 3 then 7" in a log or a test failure says something.
- Seed data, the README's `curl` examples and assertion messages all read.
- It stays visually distinct from the Idempotency Keys, which §5 already fixes as
  UUIDs. Two kinds of UUID on the wire are told apart only by field name.

**The cost is that IDs are enumerable, and that is genuinely free here.** §1
models no owner and §2 stubs authentication, and ticket 10's list endpoint hands
every account to any caller, so there is nothing an enumerated ID reaches that
the list does not already give away.

**Rejected — an internal `bigint` key plus a separate public account number.** The
shape a real bank has, and the right answer the moment ownership exists. Today it
buys a second column, a unique index, a lookup-by-public-id path and a translation
step in every endpoint and in the frontend types, to draw a boundary this context
has no second side to. If ownership ever arrives, adding an opaque public handle
then is a migration and an index, not a redesign.

---

## §1 was wrong about `record`, and the way it is wrong is the point

§1 said *"`Account` is a Java `record`, so adding an `owner` field later is
additive and non-breaking."* The clause after the comma is true. The claim before
it is not, and the sentence has been corrected in place.

The interesting part is not that it fails but **how late**. A `record` carrying
`@Entity`, `@Id`, `@GeneratedValue` and the `@AttributeOverride`s:

| gate | result |
|---|---|
| `javac` | compiles |
| Hibernate metadata, application startup | **boots** |
| `ddl-auto=validate` against `V1__accounts.sql` | **passes** |
| the first `persist` | `PropertyAccessException: Could not set value of type [java.lang.Long]: 'Account.id' (setter)`, caused by `IllegalAccessException: Can not set final java.lang.Long field Account.id`, from `SetterFieldImpl.set` |

Every startup check this project owns — including the one it added `validate` for
— passes a mapping that cannot write a row. Hibernate resolves the entity, matches
all four columns, and only discovers at the first field write that a record's
components are `final`. So `Account` is a class with a `protected` no-arg
constructor, which is what JPA has always required and what the ticket built
before this was measured.

Nothing was kept as a test. A test asserting "Hibernate refuses a record entity"
would guard against an edit no one is going to make — the class shape is visible
in the file — and the finding worth keeping is this table, not an assertion.

---

## `@JdbcTypeCode` survives `@AttributeOverride`

Ticket 06 flagged this as an assumption to confirm rather than inherit: it knew
`@AttributeOverride` replaces a component's `@Column` wholesale, which is why
`Money` carries no `@Column(length = 3)`, and it expected `@JdbcTypeCode` — a
separate annotation — to survive. **It does.**

The first draft of this file got the argument wrong and is worth recording, because
it is the same mistake ticket 06 was warning against. It reasoned: the migration
declares `varchar(3)`, the context starts, therefore the type pin survived — *"had
the override dropped it, `validate` would expect a `tinyint`."* Ticket 06's own
measured table refutes that. `tinyint` is the **neither**-annotation row;
`@Enumerated(STRING)` alone passes against a `varchar` perfectly well. A booting
context proves only that *at least one* of the two annotations survived, which is
not the question. Confirming it needed the annotations taken off one at a time,
exactly as ticket 06 had done — the shipped mapping cannot answer this by itself.

Removing each in turn from `Money` and running
`AccountHoldsTwoFiguresInOneCurrencyTest`, whose columns reach `Money` *through*
two `@AttributeOverride`s:

| on the component | `validate` against the migration's `varchar(3)` |
|---|---|
| nothing | **fails**: found `character varying`, expecting `tinyint` |
| `@Enumerated(STRING)` alone | passes |
| `@JdbcTypeCode(VARCHAR)` alone | passes, and stores `'USD'` |
| both — as shipped | passes |

The third row is the answer: with `@JdbcTypeCode` as the *only* annotation the
override could have carried, `validate` still expects a `varchar`. Had the override
stripped it, that row would behave like the first and fail.

**The table is identical to ticket 06's, and that is the finding** — passing a
component's columns through `@AttributeOverride` changes nothing about which
annotation is load-bearing. `@AttributeOverride` replaces the `@Column` and leaves
the rest of the component's mapping alone.

A second thing fell out of the first row. The ticket's prose promises that a
mismatch "fails at startup naming the column", and until this experiment only the
missing-*table* case had been demonstrated, by `FlywayOwnsTheSchemaTest`. It does
name it: `wrong column type encountered in column [balance_currency] in table
[accounts]; found [character varying (Types#VARCHAR)], but expecting [tinyint
(Types#TINYINT)]`.

The four column names are the ones the overrides name — `balance_minor_units`,
`balance_currency`, `reserved_amount_minor_units`, `reserved_amount_currency` —
because without them both figures ask for the component's own `minor_units` and
`currency` and collide.

---

## Two invariants stated in the table

The migration carries named check constraints, and the point of both is that they
hold for rows the entity did not write:

- `accounts_one_currency` — the two figures agree on their currency. `Account`'s
  constructor takes one `Money` and derives the Reserved Amount's currency from
  it, so the entity has no way to disagree with itself; the constraint covers
  seed data, migrations and anything that reaches the table in SQL.

  **It is an agreement rule, not an immutability rule**, and the difference is
  worth being honest about. §31 says the currency is "immutable thereafter", and
  what actually holds that is the absence of a mutator on `Account` — there is no
  setter and no constructor that takes a currency separately, so no code path can
  redenominate an account. An `UPDATE` changing *both* currency columns together
  satisfies this constraint. Closing that would take a trigger or a
  `REVOKE UPDATE`, which is a lot of machinery to stop a hand-written statement
  nothing in the application issues; the entity surface is the real guarantee and
  the constraint is what stops the two figures drifting apart.
- `accounts_reserved_within_balance` — `reserved BETWEEN 0 AND balance`, which is
  the Available Balance floor and implies `balance >= 0` as well.

**This does not move the enforcement out of ticket 13.** The overdraft check has
to happen in the domain, under the lock, because it is what turns an
overdrawn transfer into a `422` with a message rather than a constraint violation.
What the constraint adds is that a route *around* that check is a failed write
instead of an overdrawn account — the graded concurrency requirement stated once
more in the one place no code path can skip.

Worth noting they are cheap here in a way ticket 06 might suggest they are not.
Decision 29 records that pinning `varchar` makes Hibernate's *schema export* emit
a check constraint that no insert can then satisfy on H2. That is a `create-drop`
problem specifically; hand-written constraints under `validate` behave normally,
which is what the two tests asserting them demonstrate.

---

## What this ticket deliberately did not build

- **No repository.** Ticket 12 owns the locking query and ticket 09 the write
  path; the tests here use `TestEntityManager`. A repository added now would be a
  guess at both signatures.
- **No `reserve`, `release` or `settle`.** Ticket 13 owns those, against a real
  call site, on ticket 06's principle.
- `getAvailableBalance()` and `getCurrency()` **are** here, against that same
  principle, because they are not speculative surface: the Available Balance is
  the entire reason §13 stores two figures rather than one, and the currency is
  the invariant the two figures are constructed to share. Both are asserted.
