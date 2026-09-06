# Ticket 27 — the transactional outbox

§12 settled the mechanism down to the sentence: an event row written in the same
transaction as the change, a `@Scheduled` poller publishing unsent rows through a
one-method `EventPublisher` and marking them sent, at-least-once. Building it had
to answer three things that sentence does not: what a caller in `transfers` calls
to write the row, which side of a transaction the publish happens on, and what
stops the poller racing the test suite.

Touches §12 of the [initial decisions](00-initial-decisions.md), §11 and §17 by
way of it, §30's public-surface table, and §14 — which is cheaper from here on,
because the scheduling this ticket turns on is what the expiry reaper reuses.

---

## The slice exports two public types and still exports one port

§30's line for this slice reads `outbox/ EventPublisher (public) · OutboxPoller
package-private`, and the spec's module table says the same. Taken literally that
leaves ticket 28 with no way in: `EventPublisher` is the transport — §12 is
explicit that "Kafka is the transport under `publish()`" and spec item 53 that it
is "the outbox poller" that publishes through it — and the poller is
package-private, so nothing a change in `transfers` can name writes a row.

Three shapes were considered.

**Rejected — `publish()` writes the row, and the poller drains through a
package-private transport.** This is the only shape that keeps the slice to
literally one public type. It costs §12: `publish()` would no longer be where a
broker goes, so the sentence "Kafka is the transport under `publish()`" would have
to be corrected as false rather than amended. Renaming the seam to preserve a
count in a table is the table winning an argument it was only ever summarising.

**Rejected — a Spring `@TransactionalEventListener(BEFORE_COMMIT)`.** `transfers`
raises an application event; this slice writes the row while the transaction is
still open. The public surface stays at one type, and the cost is paid in three
places: the event types migrate into `transfers`, so the outbox's own vocabulary
lives outside it; "the same transaction" becomes a property of a listener phase
rather than of a call, which is exactly the kind of guarantee §12 wanted written
down; and it is framework machinery in the one place §12 chose hand-rolled code
so that the judgement would be legible. A listener throwing at before-commit is
also a failure mode a reader has to know about.

**Chosen — a second public type that is not a port.** `OutboxEventRecorder` is
what a change calls; `EventPublisher` stays what the poller calls. There is one
implementation of the recorder, no seam and nothing to swap, so the count of
ports is still three. Ticket 07 set the precedent — it added a second public type
to `fx` without adding a second port — and ticket 16 the precedent for a slice's
§30 line being made true later rather than being wrong now.

The two halves face opposite ways, and the package's `package-info.java` says so:
recording faces inwards from `transfers`, publishing faces outwards at whatever
transport is configured. What joins them is the row.

## `MANDATORY`, because the guarantee is otherwise untestable

`OutboxEventRecorder.record` is `@Transactional(propagation = MANDATORY)`, on
`CheckLedger.openFor`'s precedent, and the reason is sharper here than there. The
entire claim of a transactional outbox is that the event and the change commit
together. A recorder that opened a transaction of its own when it found none
would pass every test that writes an event and reads it back — and would have
silently broken the one property the table exists for, in the one direction
nobody looks. `MANDATORY` turns that from a convention into a startup-time
refusal, and `AnEventCommitsWithTheChangeThatCausedItTest` asserts the refusal
alongside the rollback, because without it the rollback test could be green for
the wrong reason.

## The table points at a Transfer without a foreign key

`check_ledger` has `foreign key (transfer_id) references transfers (id)`, and the
obvious move was to copy it. It is the wrong copy. `transfers.checks` sits
*inside* the slice it points at, so its constraint runs with the dependency
direction; this slice is one `transfers` depends on, and "nothing points back" is
§30's rule. A foreign key from `outbox_events` would be the outbox pointing back
at `transfers` in the one layer the compiler cannot check.

What `transfer_id` is for is routing and correlation — the identifier a consumer
calls us back about — not a relationship this package navigates. The practical
dividend is that the outbox's own tests need no Accounts and no Transfers to
write an event, which is why they are `@DataJpaTest` slices rather than a booted
application.

## `event_type` carries a vocabulary this slice does not own

Every other enum-backed column in this schema has a named check constraint
listing its constants — `transfers_status_is_known`,
`check_ledger_check_is_known`. `outbox_events.event_type` deliberately has none,
and is a `varchar(40)` the entity maps as a `String` rather than an enum.

The outbox is transport. It knows an event has a type, a subject and a payload,
and nothing about what any of them mean. An enum here would put the vocabulary of
`transfers` inside the package `transfers` depends on, and the constraint that
came with it would make ticket 28's two event types an edit to this slice plus a
migration — rather than a use of it. The same argument settles the payload:
`record` takes an `Object` and serialises it here, because this slice owns the
column and therefore owns the decision that a payload is JSON, but it does not
own the shape inside.

**The cost is real and is not hidden by this record:** a misspelt type is a row
nobody consumes, and nothing fails. The mitigation is a convention rather than a
constraint — the slice that emits an event names its type from a constant of its
own — and the reason that is enough here is that there are two consumers, both
stand-ins, and one repository. It would not be enough with a real broker and
teams on the other side of it, which is the point at which the type belongs in a
schema registry rather than in a check constraint anyway.

## Publish outside a transaction, mark inside one

§12 says "publishes unsent rows and marks them sent" and leaves the transaction
boundaries open. There are three ways to draw them and only one of them is
at-least-once.

**Mark first, then publish** is at-most-once: a publish that then fails leaves a
row claiming it went out, and the news of a settled Transfer is lost with no
trace that it ever existed. This is the failure the outbox exists to prevent, so
it is not a trade — it is the bug.

**Both in one transaction** does not buy atomicity, because a broker publish
cannot join a database transaction — that is the premise of the whole design. It
does buy a transaction held open across network I/O, which is the shape
`AccountLocking` and `LockedPathTouchesOnlyTheDatabaseTest` exist to keep off the
locked path. Today the transport is a log line and the cost would be invisible;
the point of not doing it is that swapping in a broker is meant to be a change
under `publish()` and nothing else, and this would make it a change to the
poller's transaction shape as well.

**Chosen: publish, then mark, each mark in its own transaction.** The poller
method itself is not transactional; the read runs in the repository's own, and
`markSent` carries `@Transactional` because the poller has none to inherit. What
this accepts is a duplicate — a publish that succeeded and a mark that did not
commit means the next run publishes it again — and a duplicate is what §12 and
spec item 52 already say consumers must tolerate, because they must anyway.

`markSent` is a guarded update (`sentAt IS NULL`), on
`CheckLedgerRepository.answer`'s precedent. Nothing today can lose that race: one
instance runs one poll at a time, and `fixedDelay` measures from the end of the
previous run so two runs cannot overlap. The guard is there because the row is
the only place the fact lives, and a second instance is a *deferred* break rather
than an impossible one. It narrows what that break costs without closing it — it
stops a second timestamp being written, not a second publish. The fix for the
publish is `SELECT … FOR UPDATE SKIP LOCKED`, and it is already written down in
[deferred.md](../deferred.md).

**A publish that throws costs its own row and no other.** The run logs it, leaves
it unsent and continues. Stopping at the first failure would let one
undeliverable event hold up everything behind it, and there is no ordering
guarantee here for that to be protecting — ordering between two events on one
Transfer is one of the four deferrals. Retrying on the next run is the entire
retry policy; backoff and a dead-letter path are the other two.

The read is bounded at 100 rows a run for a reason that is only visible next to
the fourth deferral: archival. The table only grows, so an unbounded
`sentAt IS NULL` read would eventually pull the history of the system into a
scheduled method. The cap costs nothing — what it leaves behind is still unsent
and the next run is a second away.

## Scheduling is gated by a property, and the gate is on `@EnableScheduling`

This ticket turns on the first scheduler in the codebase. Left ungated it would
race the test suite: a background poller that marks rows sent turns "a publish
failure leaves the row unsent" into a test that is green until the poller gets
there first. Ticket 29 names the same hazard for the stub Fraud Detection
consumer, and asks that its absence be asserted rather than assumed.

**The gate is on `@EnableScheduling`, not on the poller bean.** `@Scheduled` does
nothing without it — no infrastructure is registered to read the annotation — so
a context with scheduling off still has the poller and can drive it by hand. A
`@ConditionalOnProperty` on the poller itself would take the bean away and with
it the ability to test the thing at all.

**A property rather than a profile**, on §25's reasoning: Spring caches one
application context per distinct configuration, and `@TestPropertySource` is how
every other test here asks for a variation, so a property costs what the repo
already pays. It is also the honest name for what the switch does — scheduling,
not testing — which is the same switch a deployment would want for an instance
that serves reads and runs no background actors. `SchedulingConfiguration` sits
in the root package beside the other three things that belong to no slice: the
clock, the security chain and the problem document advice.

In the event, the slices need no property at all: `@DataJpaTest` does not
component-scan user `@Configuration`, so the outbox's own tests never load the
scheduler. The property is what `SchedulingIsOnUnlessItIsTurnedOffTest` exercises
and what ticket 28's booted end-to-end test will need.

---

## What the build found

### Spring Boot 4 auto-configures Jackson 3, and Jackson 2 is on the classpath to trip over

`OutboxEventRecorder` serialises its payload, so it asks for a mapper. The
obvious import is `com.fasterxml.jackson.databind.ObjectMapper` — it is on the
compile classpath, it resolves, and the application then fails to start:

```
Parameter 1 of constructor in hu.bankmonitor.payments.outbox.OutboxEventRecorder
required a bean of type 'com.fasterxml.jackson.databind.ObjectMapper' that could
not be found.
```

Boot 4 moved to Jackson 3, whose coordinates and package root are both new:
`tools.jackson.core:jackson-databind:3.1.5` supplies
`tools.jackson.databind.ObjectMapper`, and that is the bean Boot registers.
Jackson 2 (`com.fasterxml.jackson.core:jackson-databind:2.21.5`) is still
present, pulled in transitively, so the wrong import is a compile-time success
and a startup-time failure. The two types have the same simple name, which is
exactly what makes an IDE's first suggestion the wrong one.

The dividend is small and worth having: Jackson 3 made its serialisation
exceptions unchecked, so `record` wraps nothing and a payload that cannot be
written as JSON propagates and rolls the change back with it — which is the right
way round, since a settlement that committed without its event is the
disagreement this table exists to prevent.

### `@DataJpaTest` filters component scanning, not the application class's `@Bean` methods

The slice's tests need a `Clock` they can hold still, and a `@TestConfiguration` supplying
one named `clock()` fails the context outright:

```
BeanDefinitionOverrideException: Invalid bean definition with name 'clock' ...
There is already [ ... GlobalPaymentServiceApplication.clock() ] bound.
```

The slice annotation excludes `@Component` classes from *scanning*, but it still locates
and loads the `@SpringBootConfiguration` class in order to find the entities and the
datasource — and that class's own `@Bean` methods come with it. So the application's
`clock()` is present in every `@DataJpaTest` context, and a fixture that happens to pick
the obvious method name collides with it.

The fix is a differently-named `@Primary` bean: the fixture wins the injection point
without redefining anything. `spring.main.allow-bean-definition-overriding=true` was
rejected — it is the blunt version, and what it buys is the silence. It would permit this
collision and every later one, including the one where a fixture overrides a bean nobody
meant it to and the test still passes.

### `@DataJpaTest` replaces the datasource, which is what isolates these tests from a live poller

Spring Boot 4's `@AutoConfigureTestDatabase` defaults to `Replace.NON_TEST`, and this
application's datasource is one it replaces: each `@DataJpaTest` context gets its own
uniquely-named in-memory H2 rather than `jdbc:h2:mem:payments`. That is why the outbox
slice tests need no scheduling property — they are isolated structurally, by the database,
and not merely by `@DataJpaTest` declining to scan `SchedulingConfiguration`. Two reasons
for the same absence, and the stronger one is not the one the design predicted.

**This does not carry to ticket 28.** A booted `@SpringBootTest` shares
`jdbc:h2:mem:payments` with a poller that is running, so its end-to-end test is exactly the
race this section's gate was built for and will need
`payments.scheduling.enabled=false` — unless it is asserting the poller's own behaviour, in
which case it wants the poller to run and must own the timing.

### H2 2.4 hands back an `OffsetDateTime`, and introspection shouts

Two facts the schema tests found, neither of them guessable from the migration:

- A `timestamp(6) with time zone` column comes out of a raw `SELECT *` as
  `java.time.OffsetDateTime`, not the `java.sql.Timestamp` the rest of this suite's JDBC
  assertions expect. Comparing against an `Instant` needs `OffsetDateTime::toInstant`.
- `information_schema.index_columns` is where an index's columns and their order live, and
  every identifier in it comes back uppercased. An assertion written against the
  lowercase names in the migration matches nothing and reads as a missing index.

### The two halves of a Logback event, and which one a log test can be fooled by

`ILoggingEvent.getMessage()` returns the raw `Published outbox event id={} ...` template;
`getFormattedMessage()` returns the line with the arguments bound into it. Only the second
is evidence. A publisher that logged its template and passed no arguments — or passed the
wrong ones — still produces exactly one `INFO` event for a test to count and a
`getMessage()` that matches whatever the template says.

So the assertion is made in both directions: the formatted message carries every field,
and it retains no `{}`, while the raw template is asserted *not* to contain the values it
would only have if they had been hard-coded into it. The first alone would pass against a
publisher that interpolated the values into a constant string and bound nothing.
