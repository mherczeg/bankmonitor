package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.transfers.checks.CheckNotRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static hu.bankmonitor.payments.common.Currency.EUR;
import static hu.bankmonitor.payments.transfers.checks.Check.FRAUD;
import static hu.bankmonitor.payments.transfers.checks.Check.MANUAL_APPROVAL;
import static hu.bankmonitor.payments.transfers.checks.Verdict.APPROVED;
import static hu.bankmonitor.payments.transfers.checks.Verdict.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole of a Transfer's life against a real database: requested and reserved, then
 * settled or rejected by the Checks that were opened with it, with both Accounts' figures
 * read back in SQL.
 *
 * <p>Every claim is asserted against the columns rather than against what
 * {@link VerdictRecording} returned, and that is the point rather than a habit. The
 * arithmetic and the movement live in two different places — {@code Account} does the sums,
 * the persistence context decides whether they were ever written — and a test phrased through
 * the returned status would agree with a design that computed both balances perfectly and
 * flushed neither.
 *
 * <p>Nothing here is transactional, per {@link TransferScenario}: each recording really
 * commits, which is the only way the balance a later assertion reads is the balance another
 * connection would see.
 *
 * <p>This is also the ticket's end-to-end test, and it is end-to-end through the domain
 * rather than over HTTP because there is no HTTP surface yet — ticket 21 is the endpoint, and
 * {@link VerdictRecording} is deliberately the seam it will sit over.
 */
class RecordedVerdictsAdvanceTheTransferTest extends TransferScenario {

	private static final long SOURCE = 1L;
	private static final long DESTINATION = 2L;
	private static final long OPENING_BALANCE = 250_00L;
	private static final long AMOUNT = 80_00L;

	@BeforeEach
	void openBothAccounts() {
		openAccount(SOURCE, OPENING_BALANCE, EUR);
		openAccount(DESTINATION, 0L, EUR);
	}

	/**
	 * The ticket's end-to-end case, and the one assertion that both halves of the debit
	 * happened: the balance fell <em>and</em> the reservation was consumed. A settlement that
	 * debited without releasing would pass a balance assertion on its own and leave the
	 * Account's Available Balance short by the amount for ever, which is exactly the bug the
	 * ticket's wording exists to prevent — so the Available Balance is asserted too, against
	 * the figure it had before the Transfer was ever requested minus what left.
	 */
	@Test
	@DisplayName("every Check approving settles the Transfer and moves the money once")
	void everyCheckApprovingSettlesTheTransfer() {
		long transfer = reserve();

		assertThat(verdicts.recordVerdict(transfer, FRAUD, APPROVED)).isEqualTo(TransferStatus.PENDING);
		assertThat(verdicts.recordVerdict(transfer, MANUAL_APPROVAL, APPROVED)).isEqualTo(TransferStatus.SETTLED);

		assertThat(statusOf(transfer)).isEqualTo("SETTLED");
		assertThat(balanceOf(SOURCE)).isEqualTo(OPENING_BALANCE - AMOUNT);
		assertThat(reservedAmountOf(SOURCE)).as("the reservation was consumed, not left behind").isZero();
		assertThat(availableBalanceOf(SOURCE)).isEqualTo(OPENING_BALANCE - AMOUNT);
		assertThat(balanceOf(DESTINATION)).isEqualTo(AMOUNT);
		assertThat(reservedAmountOf(DESTINATION)).as("nothing is reserved on the receiving side").isZero();
	}

	/**
	 * The state between the two Verdicts, asserted rather than assumed. It is the state a
	 * Transfer spends nearly all its life in, and the one a design that settled on the first
	 * approval would skip.
	 */
	@Test
	@DisplayName("one approval while another Check is outstanding moves nothing")
	void oneApprovalWhileAnotherCheckIsOutstandingMovesNothing() {
		long transfer = reserve();

		assertThat(verdicts.recordVerdict(transfer, FRAUD, APPROVED)).isEqualTo(TransferStatus.PENDING);

		assertThat(statusOf(transfer)).isEqualTo("PENDING");
		assertThat(verdictOn(transfer, FRAUD)).isEqualTo("APPROVED");
		assertThat(verdictOn(transfer, MANUAL_APPROVAL)).isNull();
		assertThat(balanceOf(SOURCE)).isEqualTo(OPENING_BALANCE);
		assertThat(reservedAmountOf(SOURCE)).isEqualTo(AMOUNT);
		assertThat(balanceOf(DESTINATION)).isZero();
	}

	/**
	 * Rejection does not wait for the outstanding Check, because nothing it could say would
	 * change the outcome and waiting would hold the operator's funds against a Transfer
	 * already known to be dead.
	 *
	 * <p>"No money moved" is asserted on both Accounts, not only the source. There is no
	 * compensating movement to write here — that is the payoff of settling asynchronously —
	 * and the way to show it is that the destination's balance never rose in the first place.
	 */
	@Test
	@DisplayName("one rejection rejects the Transfer immediately and releases the reservation")
	void oneRejectionRejectsTheTransferImmediately() {
		long transfer = reserve();

		assertThat(verdicts.recordVerdict(transfer, FRAUD, REJECTED)).isEqualTo(TransferStatus.REJECTED);

		assertThat(statusOf(transfer)).isEqualTo("REJECTED");
		assertThat(verdictOn(transfer, MANUAL_APPROVAL)).as("the outstanding Check is never asked").isNull();
		assertThat(balanceOf(SOURCE)).isEqualTo(OPENING_BALANCE);
		assertThat(reservedAmountOf(SOURCE)).isZero();
		assertThat(availableBalanceOf(SOURCE)).isEqualTo(OPENING_BALANCE);
		assertThat(balanceOf(DESTINATION)).isZero();
	}

	/**
	 * At-least-once delivery of the Verdict that settled the Transfer. The money has to have
	 * moved once, which is the substantive claim and is asserted against both balances; the
	 * redelivery is refused rather than absorbed, and {@link TransferNotPendingException}
	 * carries {@code SETTLED} so that the reporting side learns its report landed.
	 */
	@Test
	@DisplayName("the same Verdict delivered twice advances the Transfer exactly once")
	void theSameVerdictTwiceAdvancesTheTransferOnce() {
		long transfer = reserve();
		verdicts.recordVerdict(transfer, FRAUD, APPROVED);
		verdicts.recordVerdict(transfer, MANUAL_APPROVAL, APPROVED);

		assertThatThrownBy(() -> verdicts.recordVerdict(transfer, MANUAL_APPROVAL, APPROVED))
				.isInstanceOfSatisfying(TransferNotPendingException.class, refusal ->
						assertThat(refusal.getStatus()).isEqualTo(TransferStatus.SETTLED));

		assertThat(balanceOf(SOURCE)).isEqualTo(OPENING_BALANCE - AMOUNT);
		assertThat(balanceOf(DESTINATION)).isEqualTo(AMOUNT);
		assertThat(reservedAmountOf(SOURCE)).isZero();
	}

	/**
	 * A redelivery that arrives while the Transfer is still {@code PENDING} takes the other
	 * path — it reaches the ledger, whose guarded update matches no row — and the caller gets
	 * the decision the ledger already supported rather than a refusal. That the guard is real
	 * in SQL rather than only in the in-memory fake
	 * {@code RecordingAVerdictAnswersOneOutstandingCheckTest} uses is what this asserts.
	 */
	@Test
	@DisplayName("a contradicting second Verdict does not overwrite the first")
	void aContradictingSecondVerdictDoesNotOverwriteTheFirst() {
		long transfer = reserve();
		verdicts.recordVerdict(transfer, FRAUD, APPROVED);

		assertThat(verdicts.recordVerdict(transfer, FRAUD, REJECTED)).isEqualTo(TransferStatus.PENDING);

		assertThat(verdictOn(transfer, FRAUD)).isEqualTo("APPROVED");
		assertThat(statusOf(transfer)).isEqualTo("PENDING");
		assertThat(reservedAmountOf(SOURCE)).isEqualTo(AMOUNT);
	}

	/**
	 * The late Verdict, and the reason the status is read under the lock rather than left to
	 * the guarded update. A Check answering a Transfer another Check has rejected decides
	 * {@code REJECT} all over again, so the update would match nothing and the Verdict would
	 * be written silently into the ledger of a dead Transfer. Once ticket 23 expires
	 * Transfers this becomes the ordinary case rather than a corner of one.
	 */
	@Test
	@DisplayName("a Verdict on an already-terminal Transfer is refused")
	void aVerdictOnAnAlreadyTerminalTransferIsRefused() {
		long transfer = reserve();
		verdicts.recordVerdict(transfer, FRAUD, REJECTED);

		assertThatThrownBy(() -> verdicts.recordVerdict(transfer, MANUAL_APPROVAL, APPROVED))
				.isInstanceOf(TransferNotPendingException.class)
				.hasMessageContaining("REJECTED");

		assertThat(verdictOn(transfer, MANUAL_APPROVAL)).as("nothing reached the ledger").isNull();
		assertThat(statusOf(transfer)).isEqualTo("REJECTED");
	}

	@Test
	@DisplayName("a Verdict for a Transfer that does not exist is refused")
	void aVerdictForATransferThatDoesNotExistIsRefused() {
		assertThatThrownBy(() -> verdicts.recordVerdict(4_242L, FRAUD, APPROVED))
				.isInstanceOf(UnknownTransferException.class)
				.hasMessageContaining("4242");
	}

	/**
	 * The guard {@code CheckLedger.record} carries for the first conditional policy, reached
	 * the only way it can be today: every Transfer requires both Checks, so the ledger is made
	 * to be missing one. Deleting the row is not a scenario anybody expects — it is the
	 * cheapest way to produce the ledger a conditional policy will produce routinely, and the
	 * alternative is a guard nothing exercises until the ticket that needs it.
	 *
	 * <p>The refusal rolls the transaction back, so the surviving row must still be
	 * unanswered — which also shows the Verdict was refused rather than written and then
	 * regretted.
	 */
	@Test
	@DisplayName("a Verdict for a Check the Transfer does not require is refused")
	void aVerdictForACheckTheTransferDoesNotRequireIsRefused() {
		long transfer = reserve();
		database.update("DELETE FROM check_ledger WHERE transfer_id = ? AND required_check = 'FRAUD'", transfer);

		assertThatThrownBy(() -> verdicts.recordVerdict(transfer, FRAUD, APPROVED))
				.isInstanceOf(CheckNotRequiredException.class)
				.hasMessageContaining("FRAUD");

		assertThat(verdictOn(transfer, MANUAL_APPROVAL)).isNull();
		assertThat(statusOf(transfer)).isEqualTo("PENDING");
	}

	private long reserve() {
		return reservation.reserve(transferOf(AMOUNT, SOURCE, DESTINATION)).getId();
	}
}
