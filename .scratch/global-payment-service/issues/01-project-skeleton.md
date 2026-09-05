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

**Status:** ready-for-agent

- [ ] `./mvnw test` passes on a clean clone with no Docker daemon running
- [ ] The application starts on H2 and a health endpoint reports UP
- [ ] Virtual threads are enabled and the setting is justified in a comment or the README
- [ ] A test in the repo demonstrates Hibernate mapping a Java `record` as `@Embeddable`
- [ ] An OpenAPI document is served by the running application
- [ ] Either spike failing is written up in `docs/deferred.md` with its consequence
