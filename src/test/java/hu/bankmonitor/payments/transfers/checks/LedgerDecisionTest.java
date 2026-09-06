package hu.bankmonitor.payments.transfers.checks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static hu.bankmonitor.payments.transfers.checks.Check.FRAUD;
import static hu.bankmonitor.payments.transfers.checks.Check.MANUAL_APPROVAL;
import static hu.bankmonitor.payments.transfers.checks.LedgerDecision.REJECT;
import static hu.bankmonitor.payments.transfers.checks.LedgerDecision.SETTLE;
import static hu.bankmonitor.payments.transfers.checks.LedgerDecision.WAIT;
import static hu.bankmonitor.payments.transfers.checks.Verdict.APPROVED;
import static hu.bankmonitor.payments.transfers.checks.Verdict.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The orchestration rule, as a table of ledgers and what each one means.
 *
 * <p><b>No Spring context, no database, no clock</b> — which is the reason {@link
 * LedgerDecision#decide} was specified as a function over rows rather than as a step inside
 * the service that records a Verdict. Every case below is one line of setup; inside a
 * service the mixed ledger alone would be two Accounts, a Transfer and four rows written
 * through three slices, and the case most likely to be wrong would be the one most
 * expensive to write.
 */
class LedgerDecisionTest {

	private static final long ANY_TRANSFER = 1L;

	@Test
	@DisplayName("a ledger with every Check approved settles")
	void settlesWhenEveryCheckHasApproved() {
		assertThat(LedgerDecision.decide(List.of(
				answered(FRAUD, APPROVED),
				answered(MANUAL_APPROVAL, APPROVED))))
				.isEqualTo(SETTLE);
	}

	@Test
	@DisplayName("one rejection rejects, whatever the other Checks said")
	void rejectsWhenOneCheckRejectedAndAnotherApproved() {
		assertThat(LedgerDecision.decide(List.of(
				answered(FRAUD, APPROVED),
				answered(MANUAL_APPROVAL, REJECTED))))
				.isEqualTo(REJECT);
	}

	/**
	 * The claim the ordering of the two questions exists for. A ledger that is neither
	 * complete nor clean is a rejection, not a wait: nothing an outstanding Check could say
	 * would revive the Transfer, so holding the reservation open for it costs the operator
	 * money for no possible outcome.
	 */
	@Test
	@DisplayName("a rejection wins immediately, without waiting for the outstanding Checks")
	void rejectsWhileAnotherCheckIsStillOutstanding() {
		assertThat(LedgerDecision.decide(List.of(
				answered(FRAUD, REJECTED),
				outstanding(MANUAL_APPROVAL))))
				.isEqualTo(REJECT);
	}

	@Test
	@DisplayName("a ledger nobody has answered waits")
	void waitsWhenNoCheckHasAnswered() {
		assertThat(LedgerDecision.decide(List.of(
				outstanding(FRAUD),
				outstanding(MANUAL_APPROVAL))))
				.isEqualTo(WAIT);
	}

	@Test
	@DisplayName("a ledger part answered and part outstanding waits")
	void waitsWhenOneCheckHasApprovedAndAnotherHasNotAnswered() {
		assertThat(LedgerDecision.decide(List.of(
				answered(FRAUD, APPROVED),
				outstanding(MANUAL_APPROVAL))))
				.isEqualTo(WAIT);
	}

	/**
	 * A single approval settling a one-row ledger is the same rule and not a special case,
	 * which is what makes "a new Check is a line in the policy" true: the decision counts
	 * rows, it does not know how many there ought to be.
	 */
	@Test
	@DisplayName("the rule does not depend on how many Checks there are")
	void appliesTheSameRuleToALedgerOfOne() {
		assertThat(LedgerDecision.decide(List.of(answered(FRAUD, APPROVED)))).isEqualTo(SETTLE);
		assertThat(LedgerDecision.decide(List.of(answered(FRAUD, REJECTED)))).isEqualTo(REJECT);
		assertThat(LedgerDecision.decide(List.of(outstanding(FRAUD)))).isEqualTo(WAIT);
	}

	/**
	 * The one input where the rule as stated gives a dangerous answer. Nothing outstanding
	 * and nothing rejected reads as {@code SETTLE}, and a Transfer that reached this function
	 * with no rows was not checked by anybody — so the refusal is not defensive tidiness, it
	 * is refusing to move money on the strength of a query that came back empty.
	 */
	@Test
	@DisplayName("an empty ledger is refused rather than settled")
	void refusesAnEmptyLedger() {
		assertThatThrownBy(() -> LedgerDecision.decide(List.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("never empty");
	}

	private static CheckLedgerEntry answered(Check required, Verdict verdict) {
		return new CheckLedgerEntry(ANY_TRANSFER, required, verdict);
	}

	private static CheckLedgerEntry outstanding(Check required) {
		return CheckLedgerEntry.outstanding(ANY_TRANSFER, required);
	}
}
