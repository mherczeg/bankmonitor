package hu.bankmonitor.payments.transfers.checks;

/**
 * A {@link Check}'s answer for one Transfer.
 *
 * <p>There are two answers and there is no third. A Check that has not answered has no
 * Verdict at all, which the ledger records as an absent column rather than as a constant
 * here — {@link CheckLedgerEntry} has the argument for that, and it is a claim about the
 * domain: "not yet answered" is the absence of an answer, not one of the answers.
 *
 * <p>The two are not symmetrical in what they cost. {@link #APPROVED} advances nothing on
 * its own — it is the other rows that decide — while {@link #REJECTED} ends the Transfer
 * whatever the rest of the ledger says. {@link LedgerDecision} is where that asymmetry is
 * written down.
 */
public enum Verdict {

	/** This Check is satisfied. The Transfer settles once every other Check agrees. */
	APPROVED,

	/** This Check says no, and that is the whole answer: the Transfer is rejected. */
	REJECTED
}
