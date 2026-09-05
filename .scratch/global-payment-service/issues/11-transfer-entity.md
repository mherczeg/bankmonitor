# 11: The Transfer and its states

**What to build:** A Transfer is a request to move an amount of Money from one Account to
another, possibly across Currencies, and it has a lifecycle rather than an outcome:
`PENDING` while its Checks are outstanding, then terminally `SETTLED`, `REJECTED` or
`EXPIRED`. Only `SETTLED` moves money; each terminal state releases the Reserved Amount.

This ticket lands the entity, its repository and its migration (`V2__transfers.sql`) —
source and destination Account references, the amount in the source Currency, the credited
amount in the destination Currency, the status, and the timestamps. The columns the later
slices need (the Check deadline, the locked Exchange Rate and its fetch timestamp) belong
to *those* slices' migrations, not this one.

Note the vocabulary: there is no "Transaction" entity here. "Transactions" is the label of
a screen; a recorded Transfer is still a Transfer.

**Blocked by:** 02, 08

**Status:** done

- [x] The transfers table ships as this slice's migration
- [x] Status is the closed set `PENDING`, `SETTLED`, `REJECTED`, `EXPIRED`
- [x] The entity carries both the debited and the credited amount as Money
- [x] The application starts with `validate` on, proving entity and table agree
- [x] A JPA-layer test round-trips a Transfer in each status

## Comments

### Handoff — paused mid-implementation, 2026-09-05

**Where the work is.** Worktree `/data/mherczeg/projects/bankmonitor/.claude/worktrees/11-transfer-entity`,
branch `worktree-11-transfer-entity`, branched from `6f70037`. Five files, all
uncommitted and untracked — nothing has been committed, so `git status` in that worktree
is the complete inventory:

```
src/main/java/hu/bankmonitor/payments/transfers/Transfer.java
src/main/java/hu/bankmonitor/payments/transfers/TransferStatus.java
src/main/java/hu/bankmonitor/payments/transfers/TransferRepository.java
src/main/resources/db/migration/V2__transfers.sql
src/test/java/hu/bankmonitor/payments/transfers/TransferRoundTripsInEveryStatusTest.java
```

**Test state.** `./mvnw -o test -Dtest=TransferRoundTripsInEveryStatusTest` runs 10 and
fails 1. The failure is the fixture, not the code: `refusesAStatusTheDomainHasNoNameFor`
inserts `'CANCELLED'`, which is nine characters, into `status varchar(8)`, so H2 refuses
it for length — `Value too long for column "STATUS CHARACTER VARYING(8)"` — before the
check constraint it is trying to provoke ever fires. Pick a bad status that fits in eight
characters. The full suite has **not** been run yet.

---

### The finding that cost this ticket a cycle

**`in (…)` in a check constraint is broken on H2, and it fails at the first insert rather
than at the migration.** The first `V2__transfers.sql` wrote
`check (status in ('PENDING', 'SETTLED', 'REJECTED', 'EXPIRED'))`. Flyway applied it,
`validate` passed, the context booted — and then *every* insert into `transfers` failed,
valid rows included, with `Check constraint invalid: "TRANSFERS_STATUS_IS_KNOWN: "`,
which names the constraint and reads exactly like a row that broke the rule.

It is not about the row. H2 compiles a constant `in` list into a `TreeSet` whose
comparator is **the session that parsed the DDL**, and evaluating it later calls
`SessionLocal.getCompareMode()` on that session. Once Flyway's connection is gone the
session is dead, `getDatabase()` throws `The database has been closed [90098-240]`, and
H2 wraps any exception from a constraint expression as `CHECK_CONSTRAINT_INVALID`. The
root cause is four `Caused by`s down:

```
org.h2.engine.SessionLocal.getDatabase(SessionLocal.java:674)
org.h2.engine.SessionLocal.getCompareMode(SessionLocal.java:2137)
org.h2.expression.condition.ConditionInConstantSet.getValue(…:80)
org.h2.constraint.ConstraintCheck.checkRow(…:99)
```

Measured against H2 2.4.240 by creating the table on one connection, closing it, and
inserting on another — the shape Flyway and Hibernate have:

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

The last two rows are why `V1__accounts.sql` never hit this: both its constraints happen
to be shapes that survive. Nothing about that was deliberate, and the next migration
that reaches for `in` will land here again.

`= any (array[…])` is what the migration now uses — it reads almost exactly like `in`,
and unlike `regexp` it is a shape Postgres takes too, which the migration README requires
of this schema. The probes are in the session scratchpad and are not worth keeping; the
table above is the finding.

---

### Decisions taken, and the reasoning that is otherwise lost

None of this is written down yet. `docs/design-decisions/11-transfer-entity.md` **does not
exist** and is a remaining step; so is its row in `docs/design-decisions/README.md` (both
the ticket-records table and the §7/§29 rows).

1. **The Accounts are referenced by ID, not by `@ManyToOne`.** An Account is only ever
   read or written under the pessimistic lock design decision 6 requires, and an
   association is a second way to reach one that quietly skips it —
   `transfer.getSourceAccount().getBalance()` would compile and would be wrong. Ticket 12
   locks by ascending ID and ticket 13 mutates under that lock, so neither wants
   navigation. The FKs in the migration keep referential integrity without the mapping.
   Design decision 08 already assumed this shape when it wrote that the Account ID is
   "what `fromAccountId` and `toAccountId` reference".

2. **`debitedAmount` and `creditedAmount`, not `amount` and `creditedAmount`.** The pair
   has to say which side each figure belongs to; `amount` alone answers neither "which
   currency" nor "which Account". A same-currency Transfer carries the same figure twice,
   which is the honest reading — the conversion happened, at a rate of one.

3. **`createdAt` and `statusChangedAt`, both non-null.** `created_at` is the name
   `docs/deferred.md` already commits to for the pagination key, and ticket 15 orders on
   it. The second one is deliberately *not* a nullable `completedAt`: the status is the
   single answer to whether a Transfer is done, and a nullable timestamp that also implies
   it is a second answer to keep in agreement. It also avoids `Completion`, which
   `CONTEXT.md` lists under _Avoid_ for Settlement. **This is the decision I am least
   sure of** — no later ticket demands the column, and the ticket only says "the
   timestamps", plural. Worth a second opinion before it goes into an immutable migration.

4. **`requestedAt` is a constructor parameter, not `Instant.now()`.** Ticket 23 measures
   the deadline from it and its tests must drive the clock rather than sleep.

5. **No transition methods on the entity.** Ticket 08's principle: reserving is 13, the
   Verdict that settles or rejects is 20, expiry is 23, and each is a conditional update
   guarded on the status it is leaving. A `settle()` written now would be a setter for
   three callers that do not exist. The test moves the status with a JPQL bulk update
   instead, which still binds the enum through the entity's own mapping — a native
   `INSERT` of the string would only exercise the read direction.

6. **`TransferStatus` is a top-level public enum** (ticket 15's filter and the API need
   it); **`TransferRepository` is package-private and declares nothing** beyond
   `JpaRepository` — the status filter, the newest-first listing, the reaper's scan and
   the conditional update each arrive with the ticket that has a caller to shape them.

7. **Three check constraints and two foreign keys**, on ticket 08's precedent that the
   table refuses rows the entity cannot produce: `transfers_status_is_known`,
   `transfers_two_distinct_accounts` (the backstop under ticket 14's self-transfer `422`,
   not a replacement for it) and `transfers_positive_amounts` (§26's "no Transfer may
   debit the source and credit nothing"). A constraint ordering the two timestamps was
   considered and dropped as noise.

---

### Remaining steps, in order

1. Fix the nine-character fixture described above and get the file green.
2. `./mvnw -o test` — the full suite, which has not been run since `V2__` landed. Every
   `@SpringBootTest` now boots the entity against the new migration with `validate` on,
   which is the third checkbox and needs no test of its own.
3. Write `docs/design-decisions/11-transfer-entity.md` — the H2 finding with its table,
   and the seven decisions above. Add its row to the ticket-records table in
   `docs/design-decisions/README.md`, and put it against §7 and §29 in the section table.
4. Add the `in`-in-a-check-constraint trap to `src/main/resources/db/migration/README.md`,
   under *Writing the SQL*, beside the native-`ENUM` trap it rhymes with. Per `AGENTS.md`,
   that README gets the full statement and the design record gets the reasoning and the
   rejected shapes — not the other way round.
5. While in that README: its naming example says `fromAccountId` becomes `from_account_id`,
   which is now a field that does not exist. The real one is
   `sourceAccountId` → `source_account_id`, and it demonstrates the same splitting rule.
6. Tick this ticket's five checkboxes and set `Status: done`.
7. `/code-review`, then commit to `worktree-11-transfer-entity` in the repo's voice —
   the log reads `feat: the Account, holding two figures the table keeps in one currency`,
   not `feat: add Transfer entity`.

---

### Closing — what changed after the handoff, 2026-09-05

All seven steps are done and `docs/design-decisions/11-transfer-entity.md` now exists.
Two things went differently from the handoff above:

**Decision 3 was reversed.** `statusChangedAt` is gone — entity, column and assertions. It
was the decision the handoff flagged as weakest, and the answer was that no ticket in the
plan reads it while `V2` is immutable the moment it is applied. Whichever of 13, 20 or 23
first needs to record *when* a status changed adds the column in its own migration. The
design record has the full argument.

**The rebase onto `master` mattered.** This branch was cut before tickets 09 and 10
landed, and 10 changed two things under it. It wrote the first repository, so the
`@EntityScan`-narrowing breakage this ticket hit in isolation was already fixed on
`master` — and 10 explicitly rejected the `@EnableJpaRepositories` narrowing this ticket
had reached for, because `testsupport` holds ArchUnit's broken repository fixtures. All of
that collateral work was dropped in the rebase. Ticket 10 also set the repository
precedent: the bare `Repository` marker, declaring only methods with call sites, so
`TransferRepository` now declares `save` and `findById` rather than extending
`JpaRepository`.
