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

**Status:** ready-for-agent

- [ ] The idempotency table ships as this slice's migration, with a unique constraint on
      the key
- [ ] Claiming commits in its own transaction and is visible to another connection
      immediately
- [ ] A second claim of a live key fails on the constraint rather than on a read-then-write
- [ ] Reclaiming a `FAILED` key is a conditional update returning a rows-affected count
- [ ] Marking `FAILED` survives the rollback of the operation it is reporting
- [ ] JPA-layer tests cover the constraint, the conditional update and the failure marking
