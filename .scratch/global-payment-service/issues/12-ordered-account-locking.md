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

**Status:** ready-for-agent

- [ ] A repository method locks Accounts for update
- [ ] A helper orders the Accounts a Transfer touches by ascending ID, with the ordering
      rule explained in a comment naming the deadlock it prevents
- [ ] A JPA-layer test asserts the lock query is what it claims to be
- [ ] Nothing in the locked path performs I/O beyond the database
