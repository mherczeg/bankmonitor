# 12: Locking Accounts in an order that cannot deadlock

**What to build:** The repository operation that takes a pessimistic row lock on the
Accounts a Transfer touches, and the rule about the order it takes them in.

The rule is the whole point: **locks are acquired in ascending Account ID order, never by
the Account's role in the Transfer.** If one request locked "source then destination", two
Transfers moving money in opposite directions between the same pair would each hold what
the other wants. Ordering by ID means both contend for the same lock first, so deadlock is
structurally impossible rather than merely unlikely.

This is viable only because the Exchange Rate call happens outside the transaction that
holds these locks (ticket 26) — a slow provider must never be something a lock is waiting
on.

**Blocked by:** 08

**Status:** done

- [x] A repository method locks Accounts for update
- [x] A helper orders the Accounts a Transfer touches by ascending ID, with the ordering
      rule explained in a comment naming the deadlock it prevents
- [x] A JPA-layer test asserts the lock query is what it claims to be
- [x] Nothing in the locked path performs I/O beyond the database

## Comments

**Two locking statements, not one `where id in (?, ?) order by id`.** The single-statement
version reads better and does not do the job: `ORDER BY` orders the result set, while row
locks are taken as the plan produces rows, so an index or a plan change could reverse the
acquisition order without touching a line of our code. The ordering would then be a
property of the optimiser, and the whole claim of §6 is that it is a property of ours. One
`select … for update` per Account in a loop over the sorted IDs makes it an ordinary
sequencing fact. That is also why the JPA-layer test asserts the captured statement count
is exactly **2** rather than merely that a `for update` was issued — the count is what
separates the shape the argument depends on from the shape that merely looks equivalent.
The cost is one extra round trip, which is not a trade worth weakening the argument for.

**The rule has two halves and neither test can carry both.** The order is invisible to the
database — both statements are the same SQL with a different bound parameter — so the
ordering half is asserted in a plain JUnit test against a hand-written recording fake. The
`for update` suffix is invisible to the code — the Hibernate dialect appends it, so neither
the entity nor the repository method mentions it — so the locking half needs a real H2 and
a `StatementInspector`. This is the concrete reason `AccountRepository` extends the bare
`Repository` marker rather than `JpaRepository`: a one-method interface can be implemented
by hand in fourteen lines, and with `JpaRepository`'s twenty-odd inherited methods that
fake is not writable and the ordering rule would have to go through a mocking framework or
go unasserted. Ticket 08's "a repository written before its call sites is a guess at their
signatures" is what kept it to one method; this fell out as the better reason.

**`MANDATORY` propagation, because the failure it converts is the expensive one.** Called
with no transaction open, Spring gives the operation one per statement, so each lock is
released the instant its `select` returns: the call succeeds, the SQL is right, and by the
time the caller reads the Available Balance it is guarding nothing. §6's "the lock is taken
*before* the balance check" quietly stops being true, and the suite stays green. `MANDATORY`
makes a caller in ticket 13 that forgets `@Transactional` fail loudly instead.

**The same wall ticket 10 hit, hit independently.** This was built in a worktree branched
before tickets 09–11 landed, so adding a repository broke `MoneyMapsToTwoColumnsTest` and
`RecordAsEmbeddableSpikeTest` with `Not a managed type: class ...accounts.Account` — both
narrow `@EntityScan` to a test-only host entity, and `@DataJpaTest`'s *repository* scan is a
separate mechanism that sees the whole application regardless. Ticket 10 got there first and
fixed it better, with `excludeAutoConfiguration = DataJpaRepositoriesAutoConfiguration.class`
as well as the `spring.data.jpa.repositories.enabled=false` property; this branch had only
the property, and the merge dropped it in favour of ticket 10's. Nothing of it survives in
the diff. Worth recording anyway: two independent runs into the same wall is what makes the
rule worth stating rather than a one-off.

**What the review caught.** Three things in the reachability rule, two fixed and one
accepted. (1) The origin was hard-coded to `AccountLocking`, and the review argued the real
lock holder is the caller, since that is where the transaction lives. Half right — but
widening to callers is the wrong fix and the reason is worth keeping: ticket 13's service
will legitimately hold an Exchange Rate port and call it in the phase *before* the
transaction, exactly as §4 requires, and a reachability walk cannot tell the phases apart,
so it would fail the correct design. The origin is now a named `LOCK_HOLDERS` list whose
Javadoc says this, so ticket 13 adds an entry instead of re-deriving the argument. (2) The
walk cannot see through an interface, which is how Spring wires nearly everything; what
covers it is that the predicate bans the `fx` and `mockfx` *packages* rather than client
types, so a port is itself the forbidden dependency — true only while provider slices stay
where §30 puts them, now stated. (3) The negative fixture proves the *walk* is falsifiable,
not the production rule, since it runs a different scope from a different origin. Accepted:
the only thing that would prove the latter is a class in `accounts` that exists permanently
in order to be wrong. The predicate also gained `java.io..`, which its Javadoc had been
claiming without listing. On the standards axis: `RateLookup` was renamed
`ExchangeRateLookup` (`CONTEXT.md` lists *Rate* under **Exchange Rate — Avoid**), §30 got
the pointer its precedent from ticket 07 requires, and the README paragraph lost the
optimiser argument to the design record, keeping the full statement of the rule and
shedding the reasoning — which is the direction `AGENTS.md` says deduplication has to run.

**Left deliberately undone.** The two-thread "opposing transfers do not deadlock" test is
ticket 13's: it needs the Transfer entity from 11, a non-transactional test so two threads
can see each other's locks, and a latch to hold each after its first acquisition. This
ticket's own checkbox asks for a JPA-layer assertion about the query, which is what is
here; until 13, the argument rests on the ordering property plus §6's reasoning. No
`ProblemType` constant for either exception — both answers are ticket 14's, and a URN
chosen here would be chosen without the endpoint that has to return it. Nothing added to
`CONTEXT.md`: §6 itself says a lock is infrastructure and a reservation is domain state, so
`LockedAccounts` is deliberately not domain vocabulary.

**What the rebase changed.** Master gained tickets 09, 10 and 11 while this was paused, and
three of them touched the same ground. Ticket 09 had already created `AccountRepository`,
anticipating this one in its own Javadoc — *"Ticket 12's ascending-ID locking query arrives
with its own"* — so the resolution was to add `findAndLockById` to their interface rather
than keep a competing file. That makes the interface four methods, which is worth noting
against the argument above: the hand-written fake now has three methods to refuse, and it
throws on all of them, because reaching the list or the write path from inside the locked
path would be a bug the fake should not let pass silently. `AccountLocking`'s Javadoc also
lost a sentence that had become false — it claimed the repository offered no way to read an
Account for writing without the lock, which stopped being true when ticket 09 added `save`.
What still holds, and is what the sentence now says, is the boundary that matters: the
repository is package-private, so from outside `accounts` there is no route to a balance
except through this class.
