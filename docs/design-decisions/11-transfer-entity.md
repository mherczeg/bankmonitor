# Ticket 11 — the Transfer entity

The second table and the first entity with a lifecycle. §7's four states as
built, the shape the Account references take, and a check-constraint idiom that
is quietly broken on H2.

Touches §7 and §29 of the [initial decisions](00-initial-decisions.md).

---

## `in (…)` in a check constraint is broken on H2, and it fails at the first insert

The first `V2__transfers.sql` wrote the obvious thing:

```sql
constraint transfers_status_is_known
    check (status in ('PENDING', 'SETTLED', 'REJECTED', 'EXPIRED'))
```

Flyway applied it. `validate` passed. The context booted. And then *every* insert
into `transfers` failed, valid rows included, with

```
Check constraint invalid: "TRANSFERS_STATUS_IS_KNOWN: "
```

which names the constraint and reads exactly like a row that broke the rule. It
is not about the row. H2 compiles a constant `in` list into a `TreeSet` whose
comparator belongs to **the session that parsed the DDL**, and evaluating it
later calls `SessionLocal.getCompareMode()` on that session. Once Flyway's
connection is gone the session is dead, `getDatabase()` throws
`The database has been closed [90098-240]`, and H2 wraps any exception raised
inside a constraint expression as `CHECK_CONSTRAINT_INVALID`. The root cause is
four `Caused by`s down:

```
org.h2.engine.SessionLocal.getDatabase(SessionLocal.java:674)
org.h2.engine.SessionLocal.getCompareMode(SessionLocal.java:2137)
org.h2.expression.condition.ConditionInConstantSet.getValue(…:80)
org.h2.constraint.ConstraintCheck.checkRow(…:99)
```

Measured against H2 2.4.240 by creating the table on one connection, closing it,
and inserting on another — the shape Flyway and Hibernate have between them:

| constraint shape | survives the migration connection closing |
|---|---|
| `status in ('PENDING', 'SETTLED')` | **no** |
| `status = 'PENDING' or status = 'SETTLED' or …` | **no** — H2 folds an `or` chain of equalities on one column into the same constant set |
| `n in (1, 5, 10)` | **no** — not a string-only problem |
| `status = any (array['PENDING', …])` | yes |
| `case when status = 'PENDING' then true … else false end` | yes |
| `status regexp '^(PENDING\|SETTLED\|REJECTED\|EXPIRED)$'` | yes |
| `status = 'PENDING'` (single comparison) | yes |
| `balance_currency = reserved_amount_currency` (column to column) | yes |
| `n between 0 and 10` | yes |

The last two rows are why [ticket 08](08-account-entity.md)'s constraints never
hit this. Both of `V1__accounts.sql`'s happen to be shapes that survive, and
nothing about that was deliberate — the next migration reaching for `in` would
have landed here again, which is why the rule is stated in
[`db/migration/README.md`](../../src/main/resources/db/migration/README.md)
rather than only here.

**`= any (array[…])` is what the migration uses.** It reads almost exactly like
`in`, and unlike `regexp` or a `case` expression it is a shape Postgres takes
too, which that README requires of this schema. The probes were throwaway and
are not in the repository; the table above is the finding.

The cost of *not* finding this is worth stating, because it is the reason a
ticket lost a cycle to it: every gate this project owns passes. The migration
applies, the schema validates, the application starts, and a `@DataJpaTest` that
only reads is green. The failure arrives at the first write, wearing the name of
the constraint it is not actually violating.

---

## The Accounts are referenced by ID, not by `@ManyToOne`

Two `bigint` columns with foreign keys, and two `Long` fields. No association,
in either direction.

§6 locks Accounts pessimistically, in ascending ID order, and an association is
a second way to reach one that quietly skips the lock:
`transfer.getSourceAccount().getBalance()` would compile, would return a number,
and would be wrong. Ticket 12 owns the locking query and ticket 13 mutates under
it; neither wants navigation, and a mapping that offers it invites the version
of ticket 13 that forgets.

The foreign keys stay — referential integrity is the database's job whether or
not Hibernate knows about the relationship, and
`refusesATransferAgainstAnAccountThatDoesNotExist` asserts one of them fires.
[Ticket 08](08-account-entity.md) already assumed this shape when it recorded
that the Account ID is "what `fromAccountId` and `toAccountId` reference"; this
is that assumption built.

**Rejected — `@ManyToOne(fetch = LAZY)` with the lock taken separately.** It
would give the entity a readable `getSourceAccount()`, and every reader would
then have to know which of two paths to an Account is the safe one. The mapping
that has no unsafe path is smaller than the convention that says not to use it.

---

## Two amounts, named for the sides they belong to

`debitedAmount` and `creditedAmount`, both `Money`, four columns.

§15 locks the Exchange Rate at request time, so both figures are known when the
Transfer is created and neither is derived later. The pair has to say which side
each belongs to: `amount` alone answers neither "in which currency" nor "against
which Account", and a Transfer between a EUR Account and a HUF one has two
correct answers to "how much".

A same-currency Transfer carries the same figure twice. That is the honest
reading rather than a redundancy to normalise away — the conversion happened, at
a rate of one — and the alternative, a nullable `creditedAmount` meaning "same as
debited", puts a branch in every reader to save eight bytes.

---

## One timestamp

`created_at`, non-null, and nothing else.

`docs/deferred.md` already commits to that name for the pagination key and
ticket 15 orders the Transactions list on it, so it is load-bearing today.

A `status_changed_at` beside it was built, and then dropped before the commit.
The argument for it was audit legibility — "when did this settle" answerable from
the row — and the argument against it won: no ticket in the plan reads it, and
V2 is immutable the moment it is applied. Tickets 13, 20 and 23 each advance the
status with a conditional update, and whichever of them first needs to record
*when* can add the column in its own migration, with a caller to shape it. The
status is the single answer to whether a Transfer is done; a second column
implying the same thing is a second answer to keep in agreement with the first.

Note what it was *not* called. `completedAt` would have been the reflex, and
`CONTEXT.md` lists Completion under _Avoid_ — Settlement is the word — so the
name would have had to be argued about before the column could be justified.
Dropping it settles both questions at once.

---

## Three check constraints, on ticket 08's precedent

The table refuses rows the entity cannot produce:

- `transfers_status_is_known` — the closed set of §7. Named so a violation says
  which rule broke, and written with `= any (array[…])` for the reason above.
- `transfers_two_distinct_accounts` — the backstop under ticket 14's
  self-transfer `422`, not a replacement for it. The same division ticket 08
  drew for the overdraft check: the domain refuses it with a message, the
  constraint makes a route around the domain a failed write.
- `transfers_positive_amounts` — §16's "no Transfer may debit the source and
  credit nothing", on both figures.

A fourth ordering the two timestamps was considered while there were two, and
was noise even then.

`status` is `varchar(8)`, which is exactly `REJECTED`. The width is therefore a
second gate in front of the constraint, and it fires first: a test provoking
`transfers_status_is_known` with a nine-character status gets
`Value too long for column` instead, which is a different failure wearing a
similar face. `refusesAStatusTheDomainHasNoNameFor` uses `VOIDED` for that
reason. Widening the column to hide the overlap would only buy a longer bad
status the same confusion, so the constraint keeps the tight width and the test
stays inside it.

---

## What this ticket deliberately did not build

- **No transition methods.** Ticket 13 reserves, ticket 20 settles or rejects on
  a Verdict, ticket 23 expires, and each is a conditional update guarded on the
  status it is leaving. A `settle()` written now would be a setter for three
  callers that do not exist, on [ticket 08](08-account-entity.md)'s principle.
  The round-trip test moves the status with a JPQL bulk update instead, which
  still binds the enum through the entity's own mapping — a native `INSERT` of
  the string would exercise only the read direction.
- **`TransferRepository` declares two methods**, `save` and `findById`, which are
  the two with a call site. It extends the bare `Repository` marker on
  [ticket 10](10-list-accounts-and-seed.md)'s precedent rather than
  `JpaRepository`, whose twenty-odd inherited methods would be signatures guessed
  rather than designed. The status filter, the newest-first listing, the reaper's
  overdue scan and the conditional update each transition needs arrive with the
  ticket that has a caller to shape them. It is package-private, which §30
  requires, and the compiler is what enforces that rather than a convention.
- `TransferStatus` **is** a top-level public enum rather than a nested one:
  ticket 15's filter takes it as a query parameter and the API surface names it.

---

## The slice tests this repository did not break

Worth recording because it was expected to and did not.
[Ticket 10](10-list-accounts-and-seed.md) found that `@EntityScan` narrows the
entity scan without narrowing repository scanning, so `MoneyMapsToTwoColumnsTest`
and `RecordAsEmbeddableSpikeTest` failed on the first repository the application
gained. Its fix was
`@DataJpaTest(excludeAutoConfiguration = DataJpaRepositoriesAutoConfiguration.class)`
— repository support off entirely in two tests that never wanted one — and it
recorded that this "does not need revisiting when a second repository is
written."

`TransferRepository` is that second repository, and the claim held: both tests
stayed green with no change. Ticket 10 also rejected the narrowing this ticket
would otherwise have reached for, `@EnableJpaRepositories` aimed at the fixture
package, because `hu.bankmonitor.testsupport` holds ArchUnit's deliberately
broken `AccountStore` and `LeakyRepository` and pointing repository scanning
there makes Spring Data try to build them. That rejection is load-bearing rather
than incidental, and it is why nothing under `testsupport` moved for this ticket.
