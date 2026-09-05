# Ticket 03 — package skeleton and ArchUnit

Eight packages per §30, each carrying a `package-info` that states what belongs
in it and what its public surface is. Nothing in §30 needed revising; what
follows is what writing the boundary test taught, which the design record did
not anticipate.

Touches §30 of the [initial decisions](00-initial-decisions.md).

---

## Most of the boundary needs no test

Java's default access level is package-private and the compiler enforces it, so
`ModuleBoundariesHoldTest` covers only what the compiler cannot see: cycles
between slices, and a controller holding a repository. §30 called for "~10
lines of ArchUnit"; the two rules are about that, and the rest of the file is
the part below.

## A rule that matches nothing is a violation, not a vacuous pass

ArchUnit reports an empty match as a failure, so both rules need
`allowEmptyShould(true)` to be writable before the first controller exists. The
cost is the mirror image: a rule that later *stops* matching anything still
passes, which would make the boundary test quietly stop guarding.

So the ticket's "demonstrate it fails when violated" step is a test rather than
an errand — both rules run a second time over fixtures under
`testsupport/boundaryviolations` that break them on purpose. (Both were also
violated by hand in the production packages, failed as expected, and reverted.)

## The controller rule matches on role, not only on suffix

A `@RestController` named `AccountEndpoint` is the same mistake as a
`LeakyController`, and naming drifts before boundaries do.
