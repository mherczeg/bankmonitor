# 20: Recording a Verdict, and Settlement

**What to build:** The domain operation `recordVerdict(transferId, check, outcome)` — the
single place a Transfer's lifecycle advances. It is the seam every adapter sits over: an
HTTP callback today (ticket 21), a broker consumer later, without the domain changing.

Recording a Verdict writes it to the Check Ledger, then asks `decide` what follows:

- **Settle**: the source Account's balance **and** its Reserved Amount both fall, the
  destination Account's balance rises. The reservation is consumed, not left behind — that
  is the bug this wording exists to prevent.
- **Reject**: the Reserved Amount is released and **no money moves**. There is no
  compensating transaction to write, because nothing was ever moved. This is the payoff of
  the whole asynchronous design: "undo a payment" became "don't make one".
- **Wait**: nothing else happens.

**Advancement is a conditional update** — `UPDATE … WHERE status = ?` with a rows-affected
check — so exactly one caller wins. A Check service reporting the same Verdict twice
advances the Transfer once, which is what makes its own at-least-once delivery safe. This
is the same pattern as the failed-key reclaim and the expiry reaper: one idea used three
times, not three mechanisms.

**Blocked by:** 19

**Status:** done

- [x] All Checks approving settles the Transfer and moves money once
- [x] Settlement lowers the source balance and Reserved Amount and raises the destination
      balance
- [x] A single rejection rejects the Transfer immediately, releases the reservation and
      moves no money
- [x] The same Verdict reported twice advances the Transfer exactly once
- [x] A Verdict on an already-terminal Transfer is refused
- [x] An end-to-end test creates a Transfer, records Verdicts and asserts both Accounts'
      balances
