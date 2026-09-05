# 01: Project skeleton that builds, boots and answers

**What to build:** A Spring Boot 4 / Java 21 application a reviewer can clone and run
with `./mvnw`, with no Docker and nothing to install. It serves a health endpoint and
nothing else. Virtual threads are on from the start — they are load-bearing rather than
a nicety, because the application will later call itself over HTTP to reach the stand-in
Exchange Rate provider.

This ticket also settles the two ecosystem bets the design record flagged as "verify in
the first hour". Both are cheap now and expensive to discover late, and each must be
proved by something in the repo rather than by reading a changelog:

1. Hibernate maps a Java `record` as an `@Embeddable`. If it does not, every entity's
   shape changes.
2. `springdoc-openapi` works on Spring Boot 4. If it does not, the generated frontend
   types that join the two test seams do not exist, and the testing strategy needs
   rethinking rather than patching.

If either bet loses, record the finding and its consequence in `docs/deferred.md` and
flag it — do not quietly route around it.

**Blocked by:** None (can start immediately)

**Status:** done

- [x] `./mvnw test` passes on a clean clone with no Docker daemon running
- [x] The application starts on H2 and a health endpoint reports UP
- [x] Virtual threads are enabled and the setting is justified in a comment or the README
- [x] A test in the repo demonstrates Hibernate mapping a Java `record` as `@Embeddable`
- [x] An OpenAPI document is served by the running application
- [x] Either spike failing is written up in `docs/deferred.md` with its consequence
      — not applicable, both spikes passed; outcomes recorded in `docs/design-decisions/01-project-skeleton.md`

## Comments

**Both ecosystem bets won.** Settled on Spring Boot 4.1.1 / Hibernate 7 / Java 21,
by tests rather than by changelog:

1. **Hibernate maps a Java `record` as `@Embeddable`** — `RecordAsEmbeddableSpikeTest`
   round-trips one through H2, so Hibernate instantiates it via the canonical
   constructor. Two follow-on facts the domain needs: `@Enumerated(STRING)` does
   propagate from a record component to the mapped field, and the implicit naming
   strategy flattens `minorUnits` to a `minor_units` column — which is the column name
   ticket 02's first migration has to use.
2. **`springdoc-openapi` works on Boot 4** — but only on the **3.x line**; 2.x targets
   Boot 3. 3.1.0 is itself built against `spring-boot-starter-parent` 4.1.0.
   `OpenApiDocumentSpikeTest` asserts a known controller's path and its record response
   schema appear in the document, not merely that `/v3/api-docs` returns `200` — an
   empty-but-valid skeleton would have passed the weaker check while leaving the
   generated-types plan (ticket 32) just as impossible.

Since neither failed, `docs/deferred.md` is unchanged.

**Boot 4 API drift found while writing the tests**, recorded in `docs/design-decisions/01-project-skeleton.md`
because every tutorial predates it: `TestRestTemplate` is gone (use Spring Framework
7's `RestTestClient`); the slice annotations moved packages; starters split per slice
(`spring-boot-starter-webmvc`, not `-web`); `TestEntityManager` will not resolve as a
constructor parameter.

**The spike's most valuable finding was not the bet itself.** `@Enumerated(STRING)` on
Hibernate 7 / H2 emits a **native H2 `ENUM` column, not a `varchar`**:
`currency enum ('EUR','HUF','USD')`. Ticket 02 turns on `ddl-auto=validate`, so a
migration writing the obvious `currency varchar(3)` would fail at startup.
`RecordAsEmbeddableSpikeTest.mapsEnumComponentToNativeEnumColumn` pins the behaviour and
`docs/design-decisions/01-project-skeleton.md` records the remedy — prefer
`@JdbcTypeCode(SqlTypes.VARCHAR)` on the component over an H2-specific migration, since a
native H2 enum will not survive the Postgres verification the TODO list already lists as
a production prerequisite.

**Changed in review, before committing:**

- Dropped `spring-boot-starter-validation` and the unused `-validation-test` /
  `-actuator-test` starters. Nothing here validates anything; the first `@Valid` arrives
  with the account and transfer DTOs.
- Dropped the H2 web console. No ticket asked for it, and it would hand ticket 04's
  security chain an unauthenticated servlet to rule on. §29 already answers "how does a
  reviewer see the schema?" better: the Flyway migration directory shows it as SQL.
- **Kept** Swagger UI against the reviewer's scope-creep flag, deliberately. The task is
  graded by a person who will want to exercise the API by hand, and the `-ui` starter
  supplies it for one dependency and no code. Unlike the H2 console it serves the graded
  deliverable rather than the author's convenience. Ticket 04 still has to decide what
  its security chain does with `/swagger-ui/**`.
- Extracted `BootedApplicationTest`, so the shared application context between the two
  `@SpringBootTest` classes is structural rather than a coincidence one edit could break.
- Renamed `SpikeHolding` to `SpikeEmbeddableHost`. "Holding" is not a word CONTEXT.md
  uses, and naming it `SpikeAccount` would have implied it was a draft of the real entity.

**Decisions taken here that later tickets inherit:**

- Base package is `hu.bankmonitor.payments`, not the design record's placeholder
  `com.example.payments`. §30's snippet was updated to match.
- Maven sits at the repo root so `./mvnw test` is literally true on a clean clone; the
  React app goes in `frontend/` at ticket 31.
- Test-only entities and controllers live in `hu.bankmonitor.testsupport`, outside the
  component-scan root, and are `@Import`ed or `@EntityScan`ned explicitly. Test sources
  share the runtime classpath, so anything annotated under the app's base package joins
  every `@SpringBootTest` in the suite — which turns fatal at ticket 02, when
  `ddl-auto=validate` starts rejecting entities with no migration behind them.
- `ddl-auto` is deliberately left at the Boot default. Ticket 02 introduces Flyway and
  switches it to `validate`; setting it now would mean validating against no migrations
  and the application would not start.
- The Initializr's `contextLoads` test was deleted: `ApplicationBootsTest` proves strictly
  more, and keeping it would have cost a second cached application context for nothing.
- No README yet — ticket 44 owns it. The virtual-threads justification lives in the
  comment in `application.properties`, which the acceptance criterion allows.
- Throwaway spike material is split across two trees: the tests in
  `payments/spike/` and their fixtures in `testsupport/`. Whoever deletes the spikes has
  to touch both, and `ThreadProbeController` must survive the deletion — it is shared
  with `ApplicationBootsTest`, which is not a spike.
- `.nvmrc` pinning Node 26 came from the environment setup, not from this ticket. Ticket
  31 is where it starts mattering.
