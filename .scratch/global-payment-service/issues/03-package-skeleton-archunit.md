# 03: Package skeleton with the boundaries enforced by a test

**What to build:** The package-by-feature structure the design record settled on, as
empty packages, plus the test that makes it real. Dependencies run one way: `transfers`
depends on `accounts`, `fx`, `idempotency` and `outbox`, and nothing points back. The
stand-in Exchange Rate provider package depends on nothing at all.

The public surface of each module is deliberately small — three ports total. Java's
default access level is package-private and compiler-enforced, so crossing a module
boundary does not compile; that is the mechanism that makes this more than folder
tidying. The ArchUnit test covers what the compiler cannot: that the slices are free of
cycles, and that no controller reaches a repository directly.

**Trap to record where an implementer will hit it:** `@Transactional` on a non-public
method is silently ignored under proxy-based AOP. A *class* may be package-private, but
the `@Transactional` *method* stays public.

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] Packages exist for accounts, transfers, transfers/checks, idempotency, fx, outbox,
      the mock provider and common
- [ ] An ArchUnit test asserts the slices are cycle-free
- [ ] An ArchUnit test asserts no controller references a repository
- [ ] Both rules are demonstrated to fail when deliberately violated, then reverted
- [ ] The `@Transactional`-visibility trap is noted in the code or the README
