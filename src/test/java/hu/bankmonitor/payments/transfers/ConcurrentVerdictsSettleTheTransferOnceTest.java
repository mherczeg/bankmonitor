package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.transfers.checks.Check;
import hu.bankmonitor.testsupport.RowLockBarrier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static hu.bankmonitor.payments.common.Currency.EUR;
import static hu.bankmonitor.payments.transfers.checks.Check.FRAUD;
import static hu.bankmonitor.payments.transfers.checks.Check.MANUAL_APPROVAL;
import static hu.bankmonitor.payments.transfers.checks.Verdict.APPROVED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two threads the Transfer lock exists for: the last two Checks answering at once, and a
 * Transfer that has to settle exactly once.
 *
 * <p><b>The failure this is aimed at settles nothing rather than settling twice</b>, which is
 * why it is easy to miss. Each thread writes its own ledger row, and neither transaction can
 * see the other's until it commits — so without serialisation both read a ledger with one
 * Check still outstanding, both decide to wait, and the Transfer stays {@code PENDING} for
 * ever against a fully approved ledger, holding the operator's funds until somebody notices.
 * The conditional update cannot catch it, because neither thread ever reaches one.
 *
 * <p><b>What this test does not do is falsify that on its own</b>, and saying so is the
 * point. Deleting {@code @Lock} from {@link TransferTransitions#findAndLockById} leaves no
 * {@code select … for update} on {@code transfers} for {@link RowLockBarrier} to hold the
 * threads at — and on the waiting path there is no Account lock either, so a thread can run
 * its whole transaction without ever reaching the barrier. The threads then overlap by luck
 * rather than by construction, which is trap two in
 * {@link ConcurrentReservationsHoldTheBalanceTest}'s list. What is deterministically
 * falsifiable is {@link RecordingAVerdictLocksTheTransferTest}, which asserts the statement;
 * this test is what shows the design survives real overlap, and the two are halves of one
 * argument.
 *
 * <p>Not transactional, per {@link TransferScenario}: each thread's Verdict has to really
 * commit for the other to be able to see it.
 */
@Import(RowLockBarrier.class)
class ConcurrentVerdictsSettleTheTransferOnceTest extends TransferScenario {

	private static final long SOURCE = 1L;
	private static final long DESTINATION = 2L;
	private static final long OPENING_BALANCE = 250_00L;
	private static final long AMOUNT = 80_00L;

	/** Generous, because it is only ever reached by a deadlock, and then the test has failed. */
	private static final Duration BEFORE_GIVING_UP = Duration.ofSeconds(10);

	@Autowired
	private RowLockBarrier rowLocks;

	@BeforeEach
	void openBothAccounts() {
		openAccount(SOURCE, OPENING_BALANCE, EUR);
		openAccount(DESTINATION, 0L, EUR);
	}

	/**
	 * Both outstanding Checks approving at the same moment. Serialised behind the Transfer's
	 * row lock, whichever thread arrives second is the one that sees a fully answered ledger,
	 * so exactly one of them settles — and the one that lost still returns {@code PENDING}
	 * rather than an error, because from its side nothing went wrong.
	 *
	 * <p>The balances are the claim that matters and they are read in SQL: settling twice
	 * would debit 160.00 from an Account that only reserved 80.00, and settling not at all
	 * leaves the reservation standing against a Transfer with nothing left to answer.
	 */
	@Test
	@DisplayName("the last two Checks approving at once settle the Transfer exactly once")
	void theLastTwoChecksApprovingAtOnceSettleTheTransferOnce() throws Exception {
		long transfer = reservation.reserve(transferOf(AMOUNT, SOURCE, DESTINATION)).getId();

		List<Outcome> outcomes = race(transfer, FRAUD, MANUAL_APPROVAL);

		assertThat(outcomes).allSatisfy(outcome ->
				assertThat(outcome.refusal()).as("neither Verdict is refused").isNull());
		assertThat(outcomes).extracting(Outcome::status)
				.containsExactlyInAnyOrder(TransferStatus.PENDING, TransferStatus.SETTLED);

		assertThat(statusOf(transfer)).isEqualTo("SETTLED");
		assertThat(balanceOf(SOURCE)).isEqualTo(OPENING_BALANCE - AMOUNT);
		assertThat(reservedAmountOf(SOURCE)).isZero();
		assertThat(balanceOf(DESTINATION)).isEqualTo(AMOUNT);
	}

	/**
	 * Runs the two Verdicts on threads of their own, each held at the first row lock it asks
	 * for until the other has asked for one. Waiting on the futures with a timeout is what
	 * turns a deadlock from a suite that hangs into a test that fails.
	 */
	private List<Outcome> race(long transferId, Check first, Check second) throws Exception {
		rowLocks.holdEachThreadAtItsFirstRowLock(2);
		ExecutorService threads = Executors.newFixedThreadPool(2);
		try {
			List<Future<Outcome>> running = Stream.of(first, second)
					.map(check -> threads.submit(() -> attempt(transferId, check)))
					.toList();

			List<Outcome> outcomes = new ArrayList<>();
			for (Future<Outcome> outcome : running) {
				outcomes.add(outcome.get(BEFORE_GIVING_UP.toSeconds(), TimeUnit.SECONDS));
			}
			return outcomes;
		}
		finally {
			rowLocks.release();
			threads.shutdownNow();
		}
	}

	private Outcome attempt(long transferId, Check check) {
		try {
			return new Outcome(verdicts.recordVerdict(transferId, check, APPROVED), null);
		}
		catch (RuntimeException refusal) {
			return new Outcome(null, refusal);
		}
	}

	/** What one thread came back with: the status it left the Transfer in, or its refusal. */
	private record Outcome(TransferStatus status, RuntimeException refusal) {
	}
}
