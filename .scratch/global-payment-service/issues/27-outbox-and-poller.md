# 27: The transactional outbox

**What to build:** The mechanism that guarantees a committed change and the news of it
cannot disagree. An Outbox Event row is written **in the same transaction as the change it
describes**; a scheduled poller later publishes unsent rows and marks them sent.

Three pieces:

- **The outbox table**, with the index the poller's unsent predicate needs. Ships as this
  slice's migration.
- **`EventPublisher`** — a one-method port, the third of the system's three public ports.
  In this build `publish()` writes a structured log line. Swapping in a broker is a
  transport change under this method, **not** a replacement for the outbox: the outbox
  exists because a broker publish cannot join a database transaction.
- **The poller**, scheduled, publishing unsent rows and marking them sent.

Delivery is **at-least-once**, deliberately. A publish failure loses nothing, and consumers
deduplicate — which they must do anyway.

**Deferred, with reasoning recorded:** retry with backoff, a dead-letter path, ordering
guarantees between two events on one Transfer, and archival. Also: running more than one
instance would double-publish, which is one of the two named breaks of the single-instance
assumption.

**Blocked by:** 02

**Status:** done

- [x] The outbox table ships as this slice's migration, with the unsent index
- [x] A one-method publisher port, with the log-line implementation behind it
- [x] An event written in a transaction that rolls back is never published
- [x] The poller publishes unsent rows and marks them sent
- [x] A publish failure leaves the row unsent for the next run
