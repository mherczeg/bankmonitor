package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.testsupport.CapturingStatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;

import static hu.bankmonitor.payments.common.Currency.EUR;
import static hu.bankmonitor.payments.transfers.checks.Check.FRAUD;
import static hu.bankmonitor.payments.transfers.checks.Check.MANUAL_APPROVAL;
import static hu.bankmonitor.payments.transfers.checks.Verdict.APPROVED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * That {@link VerdictRecording} really takes a row lock on the Transfer before it touches the
 * ledger, and takes it first.
 *
 * <p><b>This is the falsifiable half of the concurrency argument</b>, and
 * {@link ConcurrentVerdictsSettleTheTransferOnceTest} is the other. Deleting {@code @Lock}
 * from {@link TransferTransitions#findAndLockById} turns this red immediately and for one
 * reason, which is the property a two-thread test cannot offer: with no {@code for update} on
 * {@code transfers} there is nothing for a barrier to hold threads at, so they run mostly
 * serially and a broken design passes. Neither test is worth much without the other.
 *
 * <p>The statements are read out of Hibernate rather than inferred from the annotation, on
 * {@code AccountLockIsASelectForUpdateTest}'s reasoning: {@code for update} is appended by the
 * dialect, so nothing in the entity or the repository mentions it, and the order two locks
 * were taken in is not recoverable from the rows they left behind. The order is the second
 * claim here — Transfer first, then Accounts ascending — and it is what makes the lock graph
 * acyclic against the reservation, which takes Account locks only.
 */
@Import(CapturingStatementInspector.class)
class RecordingAVerdictLocksTheTransferTest extends TransferScenario {

	private static final long SOURCE = 1L;
	private static final long DESTINATION = 2L;

	@Autowired
	private CapturingStatementInspector statements;

	@BeforeEach
	void openBothAccounts() {
		openAccount(SOURCE, 250_00L, EUR);
		openAccount(DESTINATION, 0L, EUR);
	}

	@Test
	@DisplayName("recording a Verdict locks the Transfer's row, before any Account's")
	void locksTheTransferRowFirst() {
		long transfer = reserve(transferOf(80_00L, SOURCE, DESTINATION)).getId();
		statements.forget();

		verdicts.recordVerdict(transfer, FRAUD, APPROVED);

		List<String> rowLocks = statements.captured().stream()
				.filter(sql -> sql.endsWith("for update"))
				.toList();

		assertThat(rowLocks)
				.as("a Verdict is decided under a lock on the Transfer it answers")
				.isNotEmpty();
		assertThat(rowLocks.getFirst())
				.contains("from transfers")
				.contains("where");
	}

	/**
	 * The same claim on the only path that takes both kinds of lock. The test above answers one
	 * Check of two, so the Transfer's is the only row lock issued and "first" is true of a list
	 * with one entry in it — which leaves the ordering claim resting on a scenario that never
	 * reaches an Account.
	 *
	 * <p>Settling is where the order can actually be violated, and where a violation would
	 * matter: an Account locked before the Transfer would put this operation's lock graph the
	 * other way round from every other path that touches both. Which of the two Accounts comes
	 * first is not asserted here because the statements carry {@code ?} rather than the
	 * identifiers — ascending order is {@code AccountLockIsASelectForUpdateTest}'s claim, and
	 * this one sits above it.
	 */
	@Test
	@DisplayName("settling locks the Transfer's row before either Account's")
	void locksTheTransferBeforeTheAccountsItSettlesAgainst() {
		long transfer = reserve(transferOf(80_00L, SOURCE, DESTINATION)).getId();
		verdicts.recordVerdict(transfer, FRAUD, APPROVED);
		statements.forget();

		verdicts.recordVerdict(transfer, MANUAL_APPROVAL, APPROVED);

		List<String> rowLocks = statements.captured().stream()
				.filter(sql -> sql.endsWith("for update"))
				.toList();

		assertThat(rowLocks)
				.as("the Transfer and both of its Accounts are locked, in that order")
				.hasSize(3);
		assertThat(rowLocks.getFirst()).contains("from transfers");
		assertThat(rowLocks.subList(1, 3)).allSatisfy(sql -> assertThat(sql).contains("from accounts"));
	}
}
