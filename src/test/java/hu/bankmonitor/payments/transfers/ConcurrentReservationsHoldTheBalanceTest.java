package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.accounts.InsufficientFundsException;
import hu.bankmonitor.testsupport.RowLockBarrier;
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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two threads design decision 6 exists for: one Available Balance that cannot be spent
 * twice, and two Transfers in opposite directions that cannot wait on each other.
 *
 * <p>These are the tests the design's central integrity claim rests on, and both of them are
 * easy to write in a shape that proves nothing. Three traps, avoided deliberately:
 *
 * <ul>
 * <li><b>A transactional test method.</b> {@link TransferScenario} is not annotated
 * {@code @Transactional}, so each thread's reservation really commits. Wrapped in one
 * transaction instead, the second thread would be looking at a connection that cannot see
 * the first thread's uncommitted work, and the race would be invisible rather than absent.
 * The price is emptying the tables by hand, which that class pays.
 * <li><b>Threads that never overlap.</b> Submitting two tasks does not make them concurrent:
 * on an in-memory database the first finishes in well under the time it takes to schedule the
 * second, and the second then reads a settled world.
 * <li><b>A latch in the wrong place.</b> Releasing both threads at the <em>start</em> of the
 * operation is not enough, and this was measured rather than assumed: with a start latch and
 * nothing else, {@link #opposingTransfersBetweenOnePairOfAccountsBothComplete} stayed green
 * after the ascending-ID rule was deleted from {@code AccountLocking}, because thread one
 * still committed before thread two acquired anything. {@link RowLockBarrier} moves the wait
 * to the only point where the ordering is observable, and its Javadoc is where that argument
 * lives.
 * </ul>
 *
 * <p>With the barrier in place both claims below are falsifiable, which is the only reason
 * to believe them: dropping {@code @Lock(PESSIMISTIC_WRITE)} fails
 * {@link #exactlyOneOfTwoConcurrentTransfersOutOfOneAccountSucceeds}, and locking by role
 * instead of by ascending ID fails
 * {@link #opposingTransfersBetweenOnePairOfAccountsBothComplete}.
 *
 * <p>Both tests run on H2, whose lock timeout is around a second where Postgres waits
 * indefinitely. Nothing here holds a lock for anything like that long — the whole transaction
 * is four statements against an in-memory table — but {@code docs/deferred.md} names running
 * this suite against Postgres as a production prerequisite rather than a nicety, and this is
 * the suite it means.
 */
@Import(RowLockBarrier.class)
class ConcurrentReservationsHoldTheBalanceTest extends TransferScenario {

	private static final long LOWER_ID = 1L;
	private static final long HIGHER_ID = 2L;

	/** Generous, because it is only ever reached by a deadlock, and then the test has failed. */
	private static final Duration BEFORE_GIVING_UP = Duration.ofSeconds(10);

	@Autowired
	private RowLockBarrier rowLocks;

	/**
	 * Two Transfers of 150.00 against an Available Balance of 200.00: either alone is
	 * covered, and the two together are not, so "both succeeded" is a distinguishable
	 * outcome rather than an arithmetic coincidence.
	 *
	 * <p>Both halves of the claim are asserted here rather than split into a test of their
	 * own, because only one of them can fail on its own. Two unlocked transactions do not
	 * over-reserve — they <em>lose</em> one of the two updates, and the Reserved Amount lands
	 * on 150.00 exactly as it should. A separate "the Account never reserves more than it
	 * holds" test therefore stays green against a design with no locking at all, which is the
	 * shape of test this ticket was warned about; the outcome count above is what catches it.
	 */
	@Test
	@DisplayName("two concurrent Transfers out of one Account: exactly one succeeds and the balance holds")
	void exactlyOneOfTwoConcurrentTransfersOutOfOneAccountSucceeds() throws Exception {
		openAccount(LOWER_ID, 200_00L, EUR);
		openAccount(HIGHER_ID, 0L, EUR);

		List<Attempt> attempts = race(
				transferOf(150_00L, LOWER_ID, HIGHER_ID),
				transferOf(150_00L, LOWER_ID, HIGHER_ID));

		assertThat(attempts).filteredOn(Attempt::succeeded).hasSize(1);
		assertThat(attempts).filteredOn(attempt -> !attempt.succeeded())
				.singleElement()
				.extracting(Attempt::refusal)
				.isInstanceOf(InsufficientFundsException.class);

		assertThat(transferRows()).singleElement()
				.satisfies(row -> assertThat(row).containsEntry("STATUS", "PENDING"));

		assertThat(balanceOf(LOWER_ID)).as("no money moved").isEqualTo(200_00L);
		assertThat(reservedAmountOf(LOWER_ID)).isEqualTo(150_00L);
		assertThat(availableBalanceOf(LOWER_ID)).isNotNegative().isEqualTo(50_00L);
	}

	/**
	 * The demonstration the ascending-ID rule exists for, and the reason
	 * {@link RowLockBarrier} is worth its weight. Held at their first lock until both have
	 * asked for one, two role-ordered Transfers would each be holding what the other wants:
	 * one thread on the lower Account waiting for the higher, the other the reverse. Ordered
	 * by ID, both ask for the lower Account first, so the loser waits holding nothing and
	 * there is no cycle to form.
	 *
	 * <p>A deadlock shows up here either as H2 refusing one of the two, which fails the
	 * "neither direction is refused" assertion, or as two threads that never return, which
	 * fails on {@link #BEFORE_GIVING_UP}.
	 */
	@Test
	@DisplayName("opposing Transfers between one pair of Accounts both complete")
	void opposingTransfersBetweenOnePairOfAccountsBothComplete() throws Exception {
		openAccount(LOWER_ID, 500_00L, EUR);
		openAccount(HIGHER_ID, 500_00L, EUR);

		List<Attempt> attempts = race(
				transferOf(100_00L, LOWER_ID, HIGHER_ID),
				transferOf(100_00L, HIGHER_ID, LOWER_ID));

		assertThat(attempts).allSatisfy(attempt -> {
			assertThat(attempt.refusal()).as("neither direction is refused").isNull();
			assertThat(attempt.transfer().getId()).as("both directions were written").isNotNull();
		});

		assertThat(reservedAmountOf(LOWER_ID)).isEqualTo(100_00L);
		assertThat(reservedAmountOf(HIGHER_ID)).isEqualTo(100_00L);
		assertThat(balanceOf(LOWER_ID)).isEqualTo(500_00L);
		assertThat(balanceOf(HIGHER_ID)).isEqualTo(500_00L);
		assertThat(transferRows()).hasSize(2);
	}

	/**
	 * Runs both reservations on threads of their own, each held at its first row lock until
	 * the other has reached one, and reports what each of them got. Waiting on the futures
	 * with a timeout is what turns a deadlock from a suite that hangs into a test that fails.
	 */
	private List<Attempt> race(ReservationRequest first, ReservationRequest second) throws Exception {
		rowLocks.holdEachThreadAtItsFirstRowLock(2);
		ExecutorService threads = Executors.newFixedThreadPool(2);
		try {
			List<Future<Attempt>> attempts = Stream.of(first, second)
					.map(request -> threads.submit(() -> attempt(request)))
					.toList();

			List<Attempt> outcomes = new ArrayList<>();
			for (Future<Attempt> attempt : attempts) {
				outcomes.add(attempt.get(BEFORE_GIVING_UP.toSeconds(), TimeUnit.SECONDS));
			}
			return outcomes;
		}
		finally {
			rowLocks.release();
			threads.shutdownNow();
		}
	}

	private Attempt attempt(ReservationRequest request) {
		try {
			return new Attempt(reservation.reserve(request), null);
		}
		catch (RuntimeException refusal) {
			return new Attempt(null, refusal);
		}
	}

	/** What one thread came back with: a Transfer or the reason there is none. */
	private record Attempt(Transfer transfer, RuntimeException refusal) {

		boolean succeeded() {
			return refusal == null;
		}
	}
}
