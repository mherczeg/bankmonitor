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

**Status:** ready-for-agent

- [ ] The application boots with `ddl-auto=validate` and an empty migration directory
- [ ] Adding an entity with no matching table fails at startup naming the missing object
- [ ] The one-migration-per-slice convention is written down where an implementer will
      see it
- [ ] Demo seed data is not part of any migration (it belongs in a dev-profile runner)
