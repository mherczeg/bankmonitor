package hu.bankmonitor.payments.transfers.checks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@link CheckLedger#record} does with an answer that has already been given, and with
 * one nobody asked for.
 *
 * <p>The guard is the whole point of the method: a Check service delivers at least once, so
 * the same Verdict arrives twice and a contradicting one can arrive after it. Both have to
 * leave the ledger saying what it said the first time — the update is guarded on the row
 * still being unanswered, so the second delivery matches no row, and what the caller gets
 * back is the decision the ledger already supported rather than a refusal.
 *
 * <p>No database and no Spring: the claims here are about what the method does with the two
 * answers the repository can give it, and a fake ledger says which of the two it got. That
 * the guarded update really is guarded in SQL is
 * {@code RecordedVerdictsAdvanceTheTransferTest}'s, and it has to be — a fake that guards
 * where the real statement does not would agree with itself.
 */
class RecordingAVerdictAnswersOneOutstandingCheckTest {

	private static final long TRANSFER = 1L;

	private final FakeLedgerRows rows = new FakeLedgerRows();

	private final CheckLedger ledger = new CheckLedger(new CheckPolicy(), rows);

	@Test
	@DisplayName("answering the last outstanding Check settles")
	void answeringTheLastOutstandingCheckSettles() {
		rows.open(Check.FRAUD, Check.MANUAL_APPROVAL);
		rows.answered(Check.FRAUD, Verdict.APPROVED);

		LedgerDecision decision = ledger.record(TRANSFER, Check.MANUAL_APPROVAL, Verdict.APPROVED);

		assertThat(decision).isEqualTo(LedgerDecision.SETTLE);
	}

	@Test
	@DisplayName("one rejection decides, whatever the other Checks have said")
	void oneRejectionDecides() {
		rows.open(Check.FRAUD, Check.MANUAL_APPROVAL);

		LedgerDecision decision = ledger.record(TRANSFER, Check.FRAUD, Verdict.REJECTED);

		assertThat(decision).isEqualTo(LedgerDecision.REJECT);
	}

	@Test
	@DisplayName("a Check answered while another is outstanding waits")
	void aCheckAnsweredWhileAnotherIsOutstandingWaits() {
		rows.open(Check.FRAUD, Check.MANUAL_APPROVAL);

		LedgerDecision decision = ledger.record(TRANSFER, Check.FRAUD, Verdict.APPROVED);

		assertThat(decision).isEqualTo(LedgerDecision.WAIT);
	}

	/**
	 * The at-least-once case. The second delivery writes nothing and is not refused, because
	 * from the Check service's point of view nothing went wrong — and the caller still needs
	 * the decision back, since the delivery that did win may have been lost on the way home.
	 */
	@Test
	@DisplayName("the same Verdict twice answers once and decides the same way")
	void theSameVerdictTwiceAnswersOnce() {
		rows.open(Check.FRAUD, Check.MANUAL_APPROVAL);
		ledger.record(TRANSFER, Check.FRAUD, Verdict.APPROVED);

		LedgerDecision decision = ledger.record(TRANSFER, Check.FRAUD, Verdict.APPROVED);

		assertThat(decision).isEqualTo(LedgerDecision.WAIT);
		assertThat(rows.verdictFor(Check.FRAUD)).isEqualTo(Verdict.APPROVED);
	}

	/**
	 * A Check does not get to change its mind, and the guard is what makes that structural
	 * rather than a rule somebody remembered: the second answer matches no unanswered row, so
	 * there is no path by which it reaches the column.
	 */
	@Test
	@DisplayName("a contradicting second Verdict does not overwrite the first")
	void aContradictingSecondVerdictDoesNotOverwriteTheFirst() {
		rows.open(Check.FRAUD, Check.MANUAL_APPROVAL);
		ledger.record(TRANSFER, Check.FRAUD, Verdict.APPROVED);

		LedgerDecision decision = ledger.record(TRANSFER, Check.FRAUD, Verdict.REJECTED);

		assertThat(rows.verdictFor(Check.FRAUD)).isEqualTo(Verdict.APPROVED);
		assertThat(decision).isEqualTo(LedgerDecision.WAIT);
	}

	/**
	 * Zero rows updated has two causes and they need opposite answers: the Check was already
	 * answered, or the Transfer never required it. Only the second is a caller naming
	 * something that does not exist, and the difference is whether the ledger holds a row for
	 * the Check at all.
	 */
	@Test
	@DisplayName("a Verdict for a Check the Transfer does not require is refused")
	void refusesAVerdictForACheckTheTransferDoesNotRequire() {
		rows.open(Check.MANUAL_APPROVAL);

		assertThatThrownBy(() -> ledger.record(TRANSFER, Check.FRAUD, Verdict.APPROVED))
				.isInstanceOf(CheckNotRequiredException.class)
				.hasMessageContaining("FRAUD");

		assertThat(rows.verdictFor(Check.MANUAL_APPROVAL)).isNull();
	}

	/**
	 * The guarded update in memory, which is the only behaviour of the repository these
	 * claims turn on: an answer reaches a row that has none, and matches nothing otherwise.
	 */
	private static final class FakeLedgerRows implements CheckLedgerRepository {

		/** One entry per row, the value being the Verdict or {@code null} while outstanding. */
		private final Map<Check, Verdict> ledger = new EnumMap<>(Check.class);

		void open(Check... required) {
			for (Check check : required) {
				ledger.put(check, null);
			}
		}

		void answered(Check check, Verdict verdict) {
			answer(TRANSFER, check, verdict);
		}

		Verdict verdictFor(Check check) {
			if (!ledger.containsKey(check)) {
				throw new AssertionError("no row for " + check);
			}
			return ledger.get(check);
		}

		@Override
		public CheckLedgerEntry save(CheckLedgerEntry entry) {
			throw new AssertionError("recording a Verdict opens no rows");
		}

		@Override
		public List<CheckLedgerEntry> findAllByTransferId(Long transferId) {
			return ledger.entrySet().stream()
					.map(row -> new CheckLedgerEntry(transferId, row.getKey(), row.getValue()))
					.toList();
		}

		@Override
		public int answer(Long transferId, Check check, Verdict verdict) {
			if (ledger.get(check) != null || !ledger.containsKey(check)) {
				return 0;
			}
			ledger.put(check, verdict);
			return 1;
		}
	}
}
