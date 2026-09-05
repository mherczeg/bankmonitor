# Ticket 12 — ordered Account locking

§6's deadlock argument, built. The rule itself was already settled; what this
ticket had to decide is **where the order lives** and **what evidence a test can
actually produce for it**, neither of which §1–§31 says anything about.

Touches §6 of the [initial decisions](00-initial-decisions.md), and confirms §4's
placement of the FX call from the other end.

---

## Two locking statements, not one `where id in (?, ?) order by id`

**One `select … for update` per Account, issued in a loop over the sorted IDs.**
The obvious alternative reads better and does not do the job.

A single statement with `order by id` orders the *result set*. Row locks are
taken as the plan produces rows, and nothing in SQL binds that to the `order by`
— the sort may happen after the scan, and an index or a plan change can reverse
the acquisition order without changing a line of our code. The ordering would
then be a property of the optimiser, and §6's whole claim is that it is a
property of *ours*.

Two statements make the order an ordinary sequencing fact about a `for` loop.
That is why `AccountLockIsASelectForUpdateTest` asserts the captured statement
count is exactly **2** rather than merely that a `for update` was issued: the
count is what distinguishes the shape the argument depends on from the shape that
merely looks equivalent.

The cost is one extra round trip per transfer. Against a database that is not
the bottleneck in a design that already moved the slow work out (§4), that is not
a trade worth making the argument weaker for.

---

## Which half of the rule each test can prove

The rule has two halves and neither test can carry both, which is why there are
two of them.

| | asserted by | how |
|---|---|---|
| the statement really is a row lock | `AccountLockIsASelectForUpdateTest` | a real H2, with Hibernate's `StatementInspector` capturing the SQL on its way to the driver |
| the lower ID goes first, whichever role it has | `AccountsLockInAscendingIdOrderTest` | a hand-written recording fake, in a plain JUnit test with no Spring at all |

**The database cannot answer the ordering question.** Both statements are the
same SQL with a different bound parameter, so a captured statement log shows two
identical strings and says nothing about which Account each one locked. The order
is only visible at the call into the repository.

**The fake cannot answer the lock question.** `for update` is appended by the
Hibernate dialect; neither the entity nor the repository method mentions it, so
nothing short of a real database can show that the annotation had any effect.

This is also the concrete reason `AccountRepository` extends the bare
`Repository` marker rather than `JpaRepository` — see below.

---

## `AccountRepository` extends `Repository`, not `JpaRepository`

[Ticket 08](08-account-entity.md) deferred the repository entirely on the
grounds that one written before its call sites is a guess at their signatures.
[Ticket 09](09-create-account-endpoint.md) then created it on the same principle
and wrote the rule down: every method has a call site today. This ticket adds
`findAndLockById` and nothing else, so the interface is four methods against
`JpaRepository`'s twenty-odd — and the twenty-odd matter here specifically,
because most of them are a way to read an Account for writing *without* taking
the lock.

A side benefit turned out to be the more valuable one: **an interface this small
can be implemented by hand.** `RecordingAccountRepository`, a static class inside
the ordering test, records the IDs it is asked to lock. With `JpaRepository` that
fake is not writable and the ordering rule would have to be asserted through a
mocking framework or not at all.

The three methods it does not use throw rather than return a stub, which is worth
doing deliberately: reaching the list or the write path from inside the locked
path would be a bug, so the fake should fail loudly rather than let it pass. That
is the small cost of the interface having grown — at one method there was nothing
to refuse.

**Rejected — asserting the order through a mock.** It would work, and it would
make the test's subject the mock's call-order verification rather than a list the
test itself can read. The fake is less machinery and a better failure message.

---

## The lock is mandatory, not merely expected

`lockForTransfer` is `@Transactional(propagation = MANDATORY)`.

A pessimistic lock exists until the transaction that took it ends. Called with no
transaction open, Spring gives the operation one of its own per statement, so
each lock is released the instant its `select` returns — the call succeeds, the
statements are right, and by the time the caller reads the Available Balance it
is guarding nothing. §6's "the lock is taken *before* the balance check" quietly
stops being true.

`MANDATORY` is the propagation that refuses to run at all without an inherited
transaction. The failure mode it converts is the expensive one: a caller in
ticket 13 that forgets `@Transactional` would otherwise produce a suite that
passes and a race in production.

`refusesToLockWithNoTransactionToHoldTheLock` asserts it, and needs
`@Transactional(propagation = NOT_SUPPORTED)` on the test method to take away the
transaction `@DataJpaTest` wraps every test in.

---

## What the operation hands back, and what it refuses

**`LockedAccounts` names the pair by role and does not expose the lock order.**
Acquisition order is the lock's business. The caller reads the source's Available
Balance and credits the destination; that it may have been the second of the two
to be locked is not a fact it should be able to depend on.

**`UnknownAccountException` carries the Account ID**, because the caller has two
of them and the answer it owes the client has to say which was wrong. Which
status and which problem type URN — ticket 05's discriminator — is ticket 14's,
and no `ProblemType` constant was added here for it.

Existence is decided *under* the lock rather than checked before it, for §6's own
reason applied one step earlier: a read taken outside the transaction can be true
when taken and false by the time the reservation is written.

**A self-transfer throws `IllegalArgumentException`.** §6 refuses A→A with a
`422` before locking and ticket 14 owns that answer; this is the backstop beneath
it, so a caller that skipped the refusal gets an error rather than a
`LockedAccounts` whose two sides are the same row. It is deliberately not a
`ProblemType` — the endpoint's `422` is the real answer and this is the assertion
that the endpoint is the only way in.

---

## §6's viability, asserted as a reachability rule

Checkbox 4 asks that nothing in the locked path perform I/O beyond the database.
That is the sentence §6 leans on when it says the design is viable *because* §4
fetches the rate in a phase of its own, and it is the one part of the ticket that
is a rule rather than a behaviour.

`LockedPathTouchesOnlyTheDatabaseTest` walks the classes reachable from
`AccountLocking` and fails if any of them depends on sockets, files, a servlet
API, Spring's HTTP client packages, or the `fx` and `mockfx` slices.

**ArchUnit's own `transitivelyDependOnClassesThat` is not usable for this.**
It follows dependencies into the JDK, and every class reaches `java.net.URL` by
way of `java.lang.Class` — so the rule matches everything and means nothing. The
walk here is a breadth-first search that stops at the boundary of
`hu.bankmonitor.payments`, which is both the answerable question and the useful
one: a call to a provider is always a class of ours holding the client.

*(ArchUnit 1.4.1 also has no `ClassesShould.transitively()` — the fluent form
does not exist, so there was nothing to fall back to.)*

Per [ticket 03](03-package-skeleton-archunit.md)'s finding, a rule that currently
matches nothing needs a violation to be measured against, so
`testsupport/boundaryviolations/lockedpath/` holds a `LockHolder` whose HTTP call
is one hop below it in a `RateLookup`. That distance is the point: a check reading
only direct dependencies would pass `LockHolder`.

The negative test does **not** pin the number of findings. One call through
`RestClient`'s fluent builder depends on each interface in the chain —
`RequestHeadersUriSpec`, `RequestHeadersSpec`, `ResponseSpec` — so the list is four
entries today and a different number after a Spring upgrade. What it asserts is
the claim: the reach is found, and every entry blames `ExchangeRateLookup` rather
than `LockHolder`.

### Three limits the review found, two fixed and one accepted

**The origin is a list, and it must not be a query.** The first draft hard-coded
`AccountLocking` as the class to walk from, and the review's reading was that the
real lock holder is the *caller* — the transaction lives there, under
`MANDATORY` — so the rule would miss ticket 13's service. Half right. Widening
the origin to callers is the wrong fix, and it is worth writing down why: ticket
13's service will legitimately hold an Exchange Rate port and call it in the
phase *before* the transaction opens, which is exactly what §4 asks for, and a
reachability walk cannot tell the two phases apart. It would fail the correct
design. The origin is now a named `LOCK_HOLDERS` list that says this in its
Javadoc, so ticket 13 adds one entry rather than rediscovering the argument.

**The walk cannot see through an interface**, which is how Spring wires almost
everything. `getDirectDependenciesFromSelf` on an interface yields its own
declarations, never its implementations, so a port whose HTTP implementation
lives in another class is invisible to the hop that would find it. What covers
the case is that `IO_BEYOND_THE_DATABASE` names the `fx` and `mockfx`
**packages** rather than any client type — the port *is* the forbidden
dependency, whoever implements it. That only holds while provider slices stay
where §30 puts them, which is now stated in the test.

**The negative fixture proves the walk is falsifiable, not the rule.** It runs a
different import scope from a different origin, so it cannot show that the
production invocation would fail if the production path went wrong. Accepted
rather than fixed: the only thing that would prove it is a class in `accounts`
that exists permanently in order to be wrong. The Javadoc now says which of the
two it establishes instead of implying both.

The predicate also gained `java.io..`, which its own Javadoc had been claiming
("sockets, files") without listing. It now describes what it bans and says why
it does not try to enumerate every HTTP client on the classpath.

---

## Narrowing `@EntityScan` does not narrow the repository scan — found twice

This ticket was built in a worktree branched before tickets 09–11, and hit the
same wall [ticket 10](10-list-accounts-and-seed.md) hit: adding a repository to
the application breaks every `@DataJpaTest` that has narrowed `@EntityScan` to a
fixture package, because the two scans are separate mechanisms and only the
entity one was narrowed.

```
Error creating bean with name 'accountRepository' … :
Not a managed type: class hu.bankmonitor.payments.accounts.Account
```

**Ticket 10 owns the finding and the better fix.** It reaches for
`@DataJpaTest(excludeAutoConfiguration = DataJpaRepositoriesAutoConfiguration.class)`
*as well as* the `spring.data.jpa.repositories.enabled=false` property; the work
here had only the property. On the merge the weaker version was dropped and
ticket 10's kept, so nothing of this is in the diff — it is recorded because two
independent runs into the same wall is the evidence that the rule is worth
stating, not a coincidence worth forgetting.

The rule, which the next repository will meet again: **a `@DataJpaTest` that
narrows `@EntityScan` has to narrow the repository scan with it.**

---

## What this ticket deliberately did not build

- **The two-thread "opposing transfers do not deadlock" test.** It is the
  demonstration the rule exists for, and it belongs to ticket 13: it needs the
  Transfer entity from ticket 11, a non-transactional test so two threads see
  each other's locks, and a latch to hold each thread after its first
  acquisition. Ticket 12's own checkbox asks for a JPA-layer assertion about the
  query, which is what is here. Until then the argument rests on the ordering
  property, which is asserted, plus the reasoning in §6.
- **Anything about lock waiting.** *Verifying the locking design against
  Postgres* is already in [deferred.md](../deferred.md), and it names the exact
  gap: H2 times a lock wait out after about a second where Postgres waits
  indefinitely. Nothing here exercises a wait at all, so nothing here narrows or
  widens that deferral — ticket 13's two-thread test is the one that will run
  into it.
- **No `ProblemType` for either exception.** Both answers are ticket 14's, and a
  URN chosen here would be chosen without the endpoint that has to return it.
