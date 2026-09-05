# 02: Flyway wired with `ddl-auto=validate`

**What to build:** Schema management that is in place before the first table exists.
Flyway owns the schema; Hibernate is set to `validate` and never generates DDL. This
lands from the first commit rather than being baselined at the end, because Hibernate's
implicit naming maps things like a source-Account reference and the Money embeddable in
ways that are cheap to catch one column at a time and expensive to catch all at once.

No tables ship here. Each later slice ships the migration for the table or column it
introduces (`V1__accounts.sql`, `V2__transfers.sql`, …). That is an amendment to
design-decisions §29, which originally called for one file carrying the whole schema up
front; the amendment and its reasoning are already recorded there.

**Blocked by:** 01

**Status:** done

- [x] The application boots with `ddl-auto=validate` and an empty migration directory
- [x] Adding an entity with no matching table fails at startup naming the missing object
- [x] The one-migration-per-slice convention is written down where an implementer will
      see it
- [x] Demo seed data is not part of any migration (it belongs in a dev-profile runner)

## Comments

**The dependency is `spring-boot-flyway`, not `flyway-core`** — and this cost a cycle
worth recording, because it fails silently. Boot 4 split auto-configuration into one
module per technology, so `org.flywaydb:flyway-core`, which is what every pre-Boot-4
answer tells you to add and what Boot's own dependency management still versions, puts the
library on the classpath with nothing to run it. There is no error and no warning: the
application starts, `validate` passes against an empty schema, and migrations simply never
run. Recorded in design decision 29.

`FlywayOwnsTheSchemaTest.flywayRunsAtStartup` is what caught it. It asks the database
whether the schema history table is there rather than asserting `spring.flyway.enabled`,
which would have passed throughout — the property was never the thing that was wrong.

**Where the convention lives:** `src/main/resources/db/migration/README.md`, next to the
migrations it governs rather than in `docs/`. It carries the two rules an implementer has
to follow at that moment — one migration per slice, no seed data — and points at design
decision 29 for the naming and column-type traps rather than restating them. A first draft
reproduced §29 in full and review caught it as a second copy to keep in sync.

**Seed data is enforced, not just documented.** `migrationsCarryNoSeedData` reads every
`db/migration/**/*.sql` on the classpath and fails on an `insert into` or `merge into`
with any whitespace between the words, naming the offending file. It asserts nothing today
and holds for the 42 tickets that follow, which is the point: the rule has to survive
slices written one at a time. Verified by dropping a migration with a newline-split
`INSERT` into the directory and watching it fail.

**A note on the test-only entity.** `EntityWithNoTable` had to move into its own package,
`testsupport/unmigrated/`, because the `@EntityScan` that boots the failure has to reach
exactly one entity Flyway knows nothing about — `SpikeEmbeddableHost` next door is another
one, and Hibernate reports whichever it meets first. Its configuration class is top-level
for a related reason: Boot's test support treats a `@Configuration` nested in a test class
as *the* configuration for that test's context, which silently replaced the application
under test on the first attempt.

The name is also a small demonstration of what §29 warns about. The first version was
`EntityWithoutAMigration`, and Hibernate's implicit naming strategy turned it into
`entity_withoutamigration` — it will not split a one-letter word off the one that follows
it.

That boot also got its own `h2:mem` database. It was first written against the shared
`jdbc:h2:mem:payments`, which is held open by the cached test context: inert while the
migration directory is empty, and from `V1__` onwards a second application running Flyway
over a live context's schema history.
