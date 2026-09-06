# 16: The idempotency record and its claim mechanics

**What to build:** The mechanism that makes a retry indistinguishable from a first call,
behind a seam narrow enough to replace. The port, which encodes the decision more
precisely than prose can:

```java
public interface IdempotentExecution {
    <T> T executeOnce(String key, String payloadHash, Supplier<T> operation);
}
```

It lives in the **service layer**, not in the request pipeline. A filter-level check
decides *before* the money-moving transaction opens, which leaves exactly the race the
requirement exists to close.

This ticket lands the storage and the claim mechanics, not yet the wiring into the
endpoint (ticket 17):

- A record keyed by the Idempotency Key, with a status of `IN_PROGRESS`, `SUCCEEDED` or
  `FAILED`, the payload hash, and the stored response.
- **A unique constraint on the key** — this is what serialises concurrent duplicates. The
  database does the mutual exclusion; no application-level lock is involved.
- The claim commits in **its own transaction**, immediately, so a second caller can see it.
- Reclaiming a `FAILED` key is a conditional update — `UPDATE … WHERE status = ?` plus a
  rows-affected check — so exactly one of several concurrent retries wins.
- **Marking a key `FAILED` needs a new transaction**, or the rollback it is reporting
  undoes the status write and strands the row at `IN_PROGRESS` forever.

Ships its own migration. The seam holds a place for named replacements: a pessimistic
select-for-update, a Redis-backed store once there is more than one instance, or folding
into the outbox.

**Blocked by:** 02

**Status:** done

- [x] The idempotency table ships as this slice's migration, with a unique constraint on
      the key
- [x] Claiming commits in its own transaction and is visible to another connection
      immediately
- [x] A second claim of a live key fails on the constraint rather than on a read-then-write
- [x] Reclaiming a `FAILED` key is a conditional update returning a rows-affected count
- [x] Marking `FAILED` survives the rollback of the operation it is reporting
- [x] JPA-layer tests cover the constraint, the conditional update and the failure marking

---

## Comments

### Built, 2026-09-06

Six files. The design record is
[`docs/design-decisions/16-idempotent-execution.md`](../../../docs/design-decisions/16-idempotent-execution.md);
this is the short version.

```
src/main/resources/db/migration/V3__idempotency_records.sql
src/main/java/hu/bankmonitor/payments/idempotency/IdempotencyRecord.java
src/main/java/hu/bankmonitor/payments/idempotency/IdempotencyStatus.java
src/main/java/hu/bankmonitor/payments/idempotency/IdempotencyRecordRepository.java
src/main/java/hu/bankmonitor/payments/idempotency/IdempotencyClaims.java
src/test/java/hu/bankmonitor/payments/idempotency/TheTableDecidesWhoHoldsAKeyTest.java
```

`./mvnw -o test` — 112 tests, 0 failures, of which 15 are this slice's.

**Scope call:** `IdempotentExecution` is quoted in this ticket but is **not** in it. The
ticket's own deliverable list is the storage and the claim mechanics, and this repository
has declared no surface without a call site since ticket 08. An interface with no
implementation and no caller is that rule's clearest violation, so the port lands with
ticket 17, which is the first thing that implements it.

### Three findings

**The Idempotency Key cannot be the primary key.** `Repository.save` is insert-or-merge
and Spring Data chooses between them by whether the identifier is set, so an entity
carrying an assigned key is saved through `merge` — a `SELECT` before every `INSERT`, and
an `UPDATE` when the row is already there. That is both the read-then-write this ticket
forbids *and* a silent overwrite of somebody else's live claim. Invisible to any test that
runs one caller, because merging an absent row produces exactly the insert you wanted.
Surrogate `id` plus a named unique constraint on the key instead.

**`markSucceeded` is `MANDATORY`, not `REQUIRED`.** §4 wants it bundled with the money
movement; `REQUIRED` also means "start one if there isn't one", which would commit the
success alone, return normally, and look like it worked — surfacing later as a replayed
`201` for money that never moved. `MANDATORY` makes that call an exception at the call
site.

**`REQUIRES_NEW` on `markFailed` has a lock cost §5 does not mention.** A new transaction
cannot see the locks the suspended one holds, so reporting the failure from inside the
failing transaction — the obvious place — waits out H2's lock timeout (measured: 2024 ms,
`CannotAcquireLockException`) and *then* leaves the row at `IN_PROGRESS`, the exact state
`markFailed` exists to prevent. Pinned by a test and warned about in the Javadoc.

### For ticket 17

- **`markSucceeded` belongs inside the phase-three transaction; `markFailed` belongs
  after it**, once that transaction has ended. See the finding above.
- `claim` lets `DataIntegrityViolationException` propagate. Catching it and reading the
  losing row is 17's, and so is `findByIdempotencyKey`, which has no caller yet.
- The stored response column is `varchar(4000)` and a check constraint ties it to
  `SUCCEEDED` in both directions: a response means finished, finished means a response.
  That forecloses storing a terminal failure's response on a `FAILED` row; it was kept
  deliberately, and relaxing it is one `alter table` in a `V4`. Ticket 17's file has the
  note.

### Review, 2026-09-06

`/mattpocock-skills:code-review` against `7cb26d2`, both axes. All six checkboxes verified
against the code rather than against the write-up. Four things changed as a result.

**The correctness one: both unguarded updates discarded the rows-affected count.**
`markSucceeded` and `markFailed` are `UPDATE … WHERE idempotency_key = ?` returning
`void`, so a call naming a key nobody claimed updated nothing and returned normally. That
is this ticket's own failure mode with the alarm disconnected — the caller is told the
claim moved, and the claim it meant to move stays `IN_PROGRESS` where §5 answers `409`
forever. Both now return `int` and `IdempotencyClaims` raises
`EmptyResultDataAccessException` on zero. Two tests added; the mutation table in the design
record has the check that they bite.

**`markFailed` throws in an error path**, which is a thing ticket 17 has to know: it runs
while an exception is on its way up, so report the original and attach this one as
suppressed. Written into the Javadoc.

**Three documentation gaps**, all of them the durable half. `package-info.java` still
announced the port this slice deliberately does not ship; §29 gained an index row without
the pointer that goes with it; §30 got neither, though the slice contradicts its tree twice
(no public type, and a JPA implementation rather than the JDBC one named there). All three
fixed.

**Two findings pushed back on, and argued in the design record rather than acted on.**
`IdempotencyRecord`'s five getters have no call site, which reads oddly beside the "no
surface without a call site" rule used to justify omitting the port — but that rule governs
boundaries, and these are package-private accessors on a package-private entity behind one.
And `markSucceeded` has no caller until ticket 17 — but without it, `response_body` and its
check constraint ship as schema no code can ever write, which is the worse thing to inherit
and the one `validate` cannot catch. Both tensions are now stated in the record instead of
left for the next reader to find.

Also: `CONTEXT.md` gained **Claim**, the vocabulary this slice coined and the glossary did
not have; and the lock-timeout test asserts `CannotAcquireLockException` rather than any
`DataAccessException`, matching what the design record says was measured.

### One correction to the initial decisions

§4's closing clause — a crashed claim being "a stale row and exactly what the retry path
exists for" — is false and has been struck. The retry path is the `FAILED` one; a crash
between phase 1 and phase 3 leaves `IN_PROGRESS`, which §5 answers with `409` for that key
forever. The comparison §4 draws still holds and is still the reason to bundle. Clearing
such a row needs a claim age and a sweeper, neither of which any ticket in the plan has, so
it is written up in [`docs/deferred.md`](../../../docs/deferred.md) along with the decision
to ship the record without a timestamp.
