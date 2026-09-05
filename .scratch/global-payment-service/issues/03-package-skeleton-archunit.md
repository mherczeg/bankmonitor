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

**Status:** done

- [x] Packages exist for accounts, transfers, transfers/checks, idempotency, fx, outbox,
      the mock provider and common
- [x] An ArchUnit test asserts the slices are cycle-free
- [x] An ArchUnit test asserts no controller references a repository
- [x] Both rules are demonstrated to fail when deliberately violated, then reverted
- [x] The `@Transactional`-visibility trap is noted in the code or the README

## Comments

**An empty package is not a thing Java can express**, so each one carries a
`package-info.java` describing what belongs in it and what its public surface is. That is
also what puts the package on the classpath where ArchUnit can see it — and the reason it
works is the opposite of the usual advice. The common claim is that javac emits a
`package-info.class` only for a package-info carrying annotations; it emits one for a
Javadoc-only file too, which the `everyPackageOfTheDesignExists` assertion depends on.
The first version of that test read `src/main/java` off the filesystem on the strength of
the wrong belief, which bound it to the process working directory for no reason.

**ArchUnit fails a rule that matched nothing.** With eight packages holding no types, both
rules would have failed on the day they were written — the controller rule matches zero
classes, and ArchUnit reports that as a violation rather than as a vacuous pass. `allowEmptyShould(true)` is the fix and is the right
semantics for a prohibition, but it has a cost worth naming: a rule that later stops
matching anything — a renamed suffix, a moved package — keeps passing silently.

**So the "demonstrate it fails" step became a test rather than an errand.** Both rules run
a second time against fixtures under `testsupport/boundaryviolations/` that break them on
purpose: two classes in sibling packages referencing each other, and a `LeakyController`
holding a `LeakyRepository`. The checkbox asked for a demonstration; a demonstration done
once by hand is gone by the next commit, and this one is what stops `allowEmptyShould`
from turning the suite green for the wrong reason.

The by-hand demonstration on production code was run as well, since that is what the
checkbox literally asks: a temporary `accounts` ↔ `transfers` pair and a temporary
controller/repository pair in `accounts`. Both rules failed, naming the cycle and the
offending pair, and both files are reverted.

**`transfers/checks` is a sub-package, not a slice.** The slice pattern
`hu.bankmonitor.payments.(*)..` folds it into `transfers`, so a dependency between the two
is not a cycle — which is correct, because a Check Ledger has no meaning apart from the
Transfer it belongs to. The list of packages the test asserts exist is therefore
deliberately not the list of slices.

**The controller rule matches on role, not only on suffix.** The obvious spelling —
`haveSimpleNameEndingWith("Controller")` depending on `…EndingWith("Repository")` — states
the rule in terms of the thing most likely to change. §30's rule is about the role, so the
predicate is `@Controller`-meta-annotated *or* `*Controller`, reaching a Spring
`Repository` subtype *or* a `*Repository`. `layering/byrole/` holds the case a suffix-only
rule waves through: a `@RestController` named `AccountEndpoint` holding an `AccountStore`.

**The test is named for its claim** (`ModuleBoundariesHoldTest`), not for its category
(`ArchitectureTest`), to match `FlywayOwnsTheSchemaTest` and `ApplicationBootsTest`.

**Left for 24.** "The stand-in Exchange Rate provider package depends on nothing at all"
is currently asserted in prose — `mockfx/package-info.java` and the README — and enforced
nowhere. A rule for it belongs with the ticket that puts classes in `mockfx`; written now
it would guard an empty package, which is the failure mode the fixtures above exist to
prevent.
