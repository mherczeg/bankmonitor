# Ticket 02 — Flyway wiring

Flyway owning the schema with `ddl-auto=validate` on from the first commit, per
§29. The decision itself needed no revision; the dependency coordinate did.

Touches §29 of the [initial decisions](00-initial-decisions.md).

---

## The dependency that looks right and does nothing

**The dependency is `spring-boot-flyway`, not `flyway-core`.** Boot 4 split
auto-configuration out of `spring-boot-autoconfigure` into one module per
technology, so the coordinates every pre-Boot-4 answer gives you put the Flyway
library on the classpath with nothing to run it. The failure has no error and no
warning: the application starts, `ddl-auto=validate` passes against an empty
schema, and the first migration silently never runs.
`FlywayOwnsTheSchemaTest.flywayRunsAtStartup` asks the database for the schema
history table rather than trusting that the dependency implies the behaviour,
which is what caught it.
