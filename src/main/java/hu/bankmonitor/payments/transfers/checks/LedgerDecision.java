package hu.bankmonitor.payments.transfers.checks;

import java.util.Collection;

/**
 * What a Transfer's Check Ledger says should happen to the Transfer next.
 *
 * <p>{@link #decide} is the whole of the orchestration rule, and it is a function of the
 * ledger rows and nothing else — no repository, no clock, no Transfer. That is a design
 * instruction rather than a testing convenience: the same three lines inside a service that
 * also loads rows and moves money would only be reachable through a database, and the
 * mixed-verdict case that matters most would be set up by writing four tables.
 *
 * <p>{@link CheckLedger#record} is the caller. It answers one Check, loads the rows and asks
 * here; {@code VerdictRecording} owns everything with a side effect. Two deliveries of the
 * same Verdict both reach this function and both get the same answer, because only the first
 * of them changed a row — which is what makes the second one safe to act on rather than
 * merely harmless.
 */
public enum LedgerDecision {

	/** Every Check has approved. The money moves, once. */
	SETTLE,

	/** A Check said no. The reservation is released and no money ever moved. */
	REJECT,

	/** Some Check has not answered, and none has refused. Nothing happens. */
	WAIT;

	/**
	 * Reads a Transfer's Check Ledger and says what follows from it.
	 *
	 * <p><b>Rejection wins immediately</b>, which is why it is asked first: once one Check
	 * has said no there is nothing the outstanding ones could say that changes the outcome,
	 * and waiting for them would hold an operator's funds against a Transfer already known
	 * to be dead. So a ledger of one rejection and three unanswered rows is {@link #REJECT},
	 * not {@link #WAIT}.
	 *
	 * <p>The second question is only whether anything is still outstanding, because the
	 * first has already excluded every rejection: answered and not rejected is approved.
	 *
	 * @param ledger every row the Transfer has, in any order
	 * @throws IllegalArgumentException if the ledger is empty. No rows at all satisfies
	 *                                  "nothing outstanding, nothing rejected" and would
	 *                                  settle a Transfer nobody checked. It cannot arise —
	 *                                  a Transfer is written with its rows in one
	 *                                  transaction, and {@link CheckLedger#openFor} refuses
	 *                                  a policy that requires none — so the only way to be
	 *                                  handed one is to have loaded the wrong Transfer's
	 *                                  ledger, and answering that with "move the money" is
	 *                                  the worst available guess.
	 */
	static LedgerDecision decide(Collection<CheckLedgerEntry> ledger) {
		if (ledger.isEmpty()) {
			throw new IllegalArgumentException("a transfer's check ledger is never empty");
		}
		if (ledger.stream().anyMatch(CheckLedgerEntry::isRejected)) {
			return REJECT;
		}
		return ledger.stream().allMatch(CheckLedgerEntry::isAnswered) ? SETTLE : WAIT;
	}
}
