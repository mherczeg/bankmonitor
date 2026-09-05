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

Until the first `V1__` file lands, startup logs `No migrations found. Are your locations
set up correctly?`. They are; the warning goes away with the first migration.

## Seed data does not go here

A migration runs everywhere Flyway runs, the test suite included, so demo accounts in one
would arrive in every test's database. Seed data belongs in a `@Profile("dev")`
`CommandLineRunner`. `FlywayOwnsTheSchemaTest.migrationsCarryNoSeedData` enforces this.

## Before you write the SQL

Read design decision 29. Two things there decide what you type in these files: Hibernate's
implicit naming strategy picks the column names you have to match, and
`@Enumerated(EnumType.STRING)` does **not** produce the `varchar` you would expect.
