# 13: Reserving funds, correctly, under concurrency

**What to build:** The domain operation at the heart of the design: a Transfer is created
`PENDING` with funds **reserved** on the source Account rather than moved. The source
Account's Reserved Amount rises; no balance changes; nothing is credited yet.

The order inside the transaction is the correctness argument:

1. Lock both Accounts, ascending by ID (ticket 12).
2. *Then* check the source Account's Available Balance against the amount.
3. Write the reservation and the `PENDING` Transfer.

The balance check happens **after** the lock, so the figure it tests against cannot be
invalidated between reading it and acting on it.

This is a named graded requirement, and the claims are about the database, so unit tests
cannot reach them. The tests here are the ones that count:

- Two concurrent Transfers out of one Account, together exceeding its Available Balance:
  exactly one succeeds, and the Account never goes negative.
- Two Transfers in opposite directions between the same pair of Accounts: both complete,
  neither deadlocks.

**Two traps that produce green tests proving nothing:** a transactional test method makes
the second thread blind to the first thread's uncommitted work, so write these
non-transactional with manual cleanup; and without a latch lining the threads up, thread
one finishes before thread two starts and the race never happens.

**Blocked by:** 11, 12

**Status:** done

- [x] Reserving raises the source Account's Reserved Amount and moves no money
- [x] The Available Balance check happens after the locks are held
- [x] A Transfer exceeding Available Balance is refused and nothing is written
- [x] Concurrent Transfers out of one Account: exactly one succeeds, balance never negative
- [x] Opposing Transfers between one pair of Accounts do not deadlock
- [x] The concurrency tests are non-transactional and use a latch to force the overlap
