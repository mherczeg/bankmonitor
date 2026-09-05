# Migrations

Flyway owns this schema. `spring.jpa.hibernate.ddl-auto=validate` is on, so Hibernate
checks the entities against whatever these files produced and never generates DDL itself.
A mismatch is a startup failure naming the table or column it could not find.

## One migration per slice

Each ticket that introduces a table or a column ships the migration for it, in the same
commit as the entity: `V1__accounts.sql`, `V2__transfers.sql`, and so on, including the
later ones that only add a column. Do not write ahead of the entities — a migration for a
table nothing maps yet is the one artefact `validate` cannot check while you are writing
it.

Numbers are global and never reused. Applied migrations are immutable: to change
something a released migration created, add the next `V`.

## Invariants belong here too

`V1__accounts.sql` carries named `check` constraints, and they are not a substitute for
the domain check above them — an overdraft has to be refused in the service, under the
lock, to reach the caller as a `422` rather than a constraint violation. What the
constraint adds is that a path *around* that check fails the write. Name them, so a
violation says which rule was broken.

Hand-written constraints are fine under `validate`. The trouble design decision 29 records
is with Hibernate's *schema export* emitting one under `create-drop`, which is a setting
this application never uses.

## Seed data does not go here

A migration runs everywhere Flyway runs, the test suite included, so demo accounts in one
would arrive in every test's database. Seed data belongs in a `@Profile("dev")`
`CommandLineRunner`. `FlywayOwnsTheSchemaTest.migrationsCarryNoSeedData` enforces this.

## Writing the SQL

Hibernate's implicit naming strategy picks the names these files have to match:
`fromAccountId` becomes `from_account_id`, and an embedded `Money` flattens into
`minor_units` / `currency` with no prefix. It will not split a one-letter word off the one
that follows it — `EntityWithoutAMigration` becomes `entity_withoutamigration`, not
`entity_without_a_migration`.

Column *types* are the sharper edge. `@Enumerated(EnumType.STRING)` on Hibernate 7 and H2
maps to a **native H2 `enum (…)` column, not a `varchar`** — a migration writing
`varchar(3)` looks obviously right, matches every tutorial written before Hibernate 6.2,
and fails `validate` at startup. Pin the mapping with `@JdbcTypeCode(SqlTypes.VARCHAR)` on
the component rather than writing an H2-specific type here; this schema still has to
survive the move to Postgres. `RecordAsEmbeddableSpikeTest` holds the evidence.

Design decision 29 has the reasoning behind all of this, and the alternatives that were
rejected, for as long as that file is around.
