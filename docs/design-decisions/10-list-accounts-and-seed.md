# Ticket 10 — listing Accounts, and having some to look at

The first business endpoint, the first repository, and the first thing a reviewer
can point a browser at. §13's derived Available Balance goes on the wire, and
§29's "seed data is not schema" gets its runner.

Touches §13, §25, §29, §30 and §31 of the [initial decisions](00-initial-decisions.md).

---

## §30's package tree is missing a class, and the ArchUnit rule says which

§30 draws `accounts/` as *"Account, AccountController · repository package-private"*
— a controller and a repository, and nothing between them. That tree cannot be
built. §30's own recommended rule, *"no controller may reference a repository"*,
was written down in the same section and is enforced by
`ModuleBoundariesHoldTest`, so a controller holding `AccountRepository` fails the
suite.

**So `accounts/` has an `AccountService`, and it forwards one call.** It is a
layer with nothing in it yet, and that is not an argument against it: the reason
the rule exists is that the service is where the transaction begins, and ticket 09
creating an account and ticket 13 reserving against one both need that boundary to
already be the place the work goes. Writing it now costs one class; discovering it
at ticket 13 costs moving a call site under a lock.

The listing's `@Transactional(readOnly = true)` is real work rather than
decoration. Without it the repository call runs in its own short transaction and
Hibernate is free to keep the returned entities managed and dirty-checkable; with
it the read is one transaction that is explicitly not allowed to write.

**The class is package-private and the method is public**, which reads backwards
and is §30's trap stated as code: proxy-based AOP silently drops `@Transactional`
from a non-public method. Demoting `listAll()` to match its class would leave a
method that reads as transactional, boots without complaint, and is not.

**The mapping to `AccountResponse` is applied by the controller, not the service.**
The factory sits on the response type as `AccountResponse.of(Account)` — a record
knowing how to be built from the entity it describes — and the controller is the
layer that calls it. What matters is which layer that is: the wire shape belongs to
the web layer, and a service returning it would put the API's field names inside
the transaction boundary, which is the same layering the ArchUnit rule is about,
one step further in.

---

## The repository extends the bare `Repository` marker

`CrudRepository` would be the reflex, and it hands the slice `save`, `saveAll`,
`findById`, `findAll`, `delete`, `deleteAll` and `count` before anything asks for
them. Spring Data's bare `Repository<T, ID>` declares **nothing**, so every method
on `AccountRepository` is one with a call site today:

| Method | Called by |
|---|---|
| `findAllByOrderByIdAsc()` | the listing |
| `saveAll(Iterable<Account>)` | the demo seeder |

The argument is the one ticket 06 made about `Money`'s four-method surface, and
ticket 08 made again in declining to write a repository at all: a surface larger
than its use is a set of signatures guessed rather than designed, and each guess is
something a reviewer has to read and a later ticket has to either honour or
delete. Ticket 09's write path and ticket 12's ascending-ID locking query arrive
with their own.

The ordering is in the method name rather than left to the database. An unordered
`findAll()` on H2 happens to come back by primary key, so the listing would look
stable and would be resting on nothing — and ticket 38's accounts screen would
reshuffle itself between two refetches of data that had not changed.

---

## Why the listing test boots the application

§25's table puts "contract test the API interface" at `@WebMvcTest`, and this
endpoint does not fit that row. The claim worth making about `GET /api/accounts`
is about the **Available Balance**, and no layer stores it: it is `balance -
reservedAmount`, computed on the way out.

To see a non-trivial one you need a row whose Reserved Amount is not zero. Nothing
can write that row. `Account`'s constructor opens an account with everything
available, and raising the Reserved Amount is ticket 13's reservation, which does
not exist. So a `@WebMvcTest` with a mocked `AccountService` would have to hand the
controller an `Account` **that the application cannot produce**, and would then
assert the subtraction against a value the test author had chosen — a test of the
mock's arithmetic.

`AccountListingTest` therefore extends `BootedApplicationTest` and inserts its rows
as SQL, the way `AccountHoldsTwoFiguresInOneCurrencyTest` already does for the same
reason. The context is the one the suite boots anyway, so §25's context-caching
trap is not paid; what is paid is that the class writes to a database other tests
read, which a `@AfterEach` empties.

**The seeded accounts have a zero Reserved Amount for exactly the same reason**,
which is worth knowing before reading the demo screen: until a Transfer reserves,
every demo account reports an Available Balance equal to its balance. That is the
system telling the truth, not the derivation failing to do anything.

---

## Two contexts that assumed the scan root was empty

Ticket 10 is the first slice to put a real repository and a real controller into
`hu.bankmonitor.payments`, and two tests written by earlier tickets had quietly
assumed there were none. Both broke on the same shape of mistake: **a Spring test
slice narrows one kind of scanning and not the other.**

**`@EntityScan` does not narrow repository scanning.**
`MoneyMapsToTwoColumnsTest` and `RecordAsEmbeddableSpikeTest` are `@DataJpaTest`s
that point `@EntityScan` at a fixture package, so `Account` is not a managed type
in their contexts. Repository scanning still ran from the application's root:

```
BeanCreationException: Error creating bean with name 'accountRepository'
  ... defined in @EnableJpaRepositories declared on DataJpaRepositoriesRegistrar
Caused by: java.lang.IllegalArgumentException:
  Not a managed type: class hu.bankmonitor.payments.accounts.Account
```

The fix is `@DataJpaTest(excludeAutoConfiguration = DataJpaRepositoriesAutoConfiguration.class)`.
Neither test uses a repository — both go through `TestEntityManager` and native SQL
— so turning repository support off entirely says what is true, and does not need
revisiting when a second repository is written.

**Rejected — `@EnableJpaRepositories` pointed at the fixture package.** The obvious
narrowing, and it fails: `hu.bankmonitor.testsupport` holds the deliberately
broken `AccountStore` and `LeakyRepository` that ticket 03's ArchUnit rules are run
against, so aiming repository scanning there makes Spring Data try to *build* them
and the context fails on `Not a managed type: class java.lang.Object`. Fixtures
written to violate a rule are still classpath, and a "narrowing" can widen.

**A bare `@WebMvcTest` component-scans every controller in the application.**
`ProblemDocumentContractTest` only ever wanted `ProblemProbeController`, which
lives outside the application's packages and arrives by `@Import`. With a real
controller in the scan root the web slice went looking for a service it
deliberately does not provide:

```
UnsatisfiedDependencyException: Error creating bean with name 'accountController'
Caused by: No qualifying bean of type 'hu.bankmonitor.payments.accounts.AccountService'
```

The fix is `@WebMvcTest(ProblemProbeController.class)`, which narrows the scan to a
class the scan cannot reach — so it matches nothing — while the `@Import` still
registers the probe. Naming a controller beside the `@Import` looks redundant and
is the annotation doing its actual job.

**The finding is not the two annotations.** It is that a slice test's narrowing is
only as narrow as its *loosest* scan, and that the assumption "there is nothing in
the scan root yet" is invisible until the day there is. Both broke on the ticket
that made the application non-empty, which is the earliest they could have, and the
cost was two annotations rather than a redesign.

---

## The dev-profile test needs a database of its own

`DemoAccountsSeedTheDevProfileTest` is a second `@SpringBootTest` context — a
profile is part of the context cache key, so §25's caching cannot be had here, and
proving that `dev` seeds anything cannot be done from inside the context every
other test shares.

It also needs its own JDBC URL. The suite's `jdbc:h2:mem:payments;DB_CLOSE_DELAY=-1`
keeps one in-memory database alive for the whole JVM precisely so contexts can
share it — so a second context seeding into it would put five demo accounts in
front of every other test that reads the accounts table, which is the exact failure
§29's "seed data is not schema" is about, arriving by a different door.
`spring.datasource.url=jdbc:h2:mem:demo-accounts-seed` is the whole fix.

`FlywayOwnsTheSchemaTest` holds the other half, and holds it by **type rather than
by name**: no `CommandLineRunner` and no `ApplicationRunner` bean exists in a
context with no profile active. Asserting `DemoAccountSeeder` in particular is
absent would stop being true the moment a second slice seeds something, and would
say nothing about it.

---

## No idempotence guard on the seeder

The obvious hardening — `if (accounts.count() > 0) return;` — is dead code here.
The database is `jdbc:h2:mem:` and dies with the process, so the runner cannot
observe a second start against rows it already wrote. A guard would be a branch no
test can enter, and `AccountRepository` would grow a `count()` with no live call
site to justify it.

It stops being dead the moment the application points at a durable database. That
is the same change that makes the seeder itself wrong — demo rows in a deployed
database — so the answer then is the profile, not the guard.

---

## The wire shape: flat, with one currency

```json
{ "id": 1, "currency": "EUR", "balanceMinorUnits": 250000,
  "reservedAmountMinorUnits": 0, "availableBalanceMinorUnits": 250000 }
```

**One `currency` for all three figures, not a nested `Money` per figure.** An
account is denominated once — §31 fixes its currency at creation and ticket 08's
`accounts_one_currency` constraint holds the table to it — so a shape with a
currency beside each amount repeats it three times and can express a state the
database refuses. Reading which of three currencies is authoritative would be the
client's problem, and there is no reason to give them one.

**Rejected — an object wrapping the array.** An envelope earns its place when there
is something to put beside the items: a cursor, a total, a page number. §31
declines pagination, so today it would be a wrapper around nothing. Adding one
later breaks the client either way, and ticket 32's generated types turn that break
into a TypeScript compile error rather than a silent one.

**Every amount is named `…MinorUnits`.** §16 keeps money as a whole count of minor
units and the per-currency decimal places live at the edges; the seed data makes the
point on its own — account 1 holds `250000` EUR minor units (€2,500.00) and account
5 holds `250000` HUF minor units (250,000 Ft). Read as a decimal, the same number is
off by a factor of a hundred in one and correct in the other, and nothing about the
number itself tells them apart. The name does.

The Available Balance is **sent** rather than left for the client to subtract,
because it is the figure ticket 13's overdraft check tests against — a client
computing it would be a second implementation of the rule, and the one that drifts.

---

## Nothing new was deferred

The two entries this endpoint would produce — [pagination](../deferred.md) and
[scoped queries](../deferred.md) — were already written against
`GET /api/accounts` by name before it existed. Building it changed neither.
