package hu.bankmonitor.testsupport;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds each thread at the first row lock it asks for until an expected number of threads
 * have asked, so that a test can arrange for several transactions to be holding one lock
 * each at the moment any of them reaches for a second.
 *
 * <p><b>Why a test needs this at all.</b> A latch that releases two threads at the start of
 * an operation lines up their <em>beginnings</em>, and nothing else. Against an in-memory
 * database the whole of a reservation — two locks, an update and an insert — runs in well
 * under the time it takes to schedule the second thread, so the first transaction routinely
 * commits before the second acquires anything, and a design that took its locks in an order
 * that can deadlock passes anyway. That is the shape of green test the reservation tickets
 * were warned about: it exercises the code twice rather than concurrently.
 *
 * <p>The latest point a test can reach is the statement on its way to the driver, which
 * Hibernate offers through {@link StatementInspector}, and a {@code select … for update} is a
 * lock acquisition about to happen. Holding threads there puts all of them inside their
 * transactions, at their first lock, in the same instant — which is as close to the
 * acquisition as anything outside the engine gets.
 *
 * <p><b>What that is not.</b> {@code inspect} runs <em>before</em> the statement executes, so
 * the barrier cannot guarantee that one thread had acquired its first lock before another
 * asked for its second: released together, a thread may still run its whole transaction
 * while the other is between the barrier and its own first row. What the barrier removes is
 * the systematic case — a thread that had not started when the other committed — and what
 * takes the argument the rest of the way is the falsification table in
 * {@code docs/design-decisions/13-reserve-funds.md}: with the barrier in place, two separate
 * defects injected into {@code AccountLocking} and {@code AccountRepository} each turn a test
 * red, repeatably. Waiting at the <em>second</em> lock instead would be a stronger guarantee
 * and a broken one — under the correct ascending-ID design the second thread is blocked in
 * the driver on its first row and never reaches a second statement to be counted.
 *
 * <p>It is <em>only</em> a test hook: nothing in {@code src/main} knows this exists, and it
 * is inert until {@link #holdEachThreadAtItsFirstRowLock} arms it. That is what makes it a
 * fair trade where a hook compiled into the production path would not have been.
 *
 * <p>Register it with {@code @Import(RowLockBarrier.class)} on the test class that needs it.
 * Doing so gives that class an application context of its own rather than the one
 * {@link BootedApplicationTest} shares, which costs one extra boot — the price of a
 * concurrency claim that can fail. <b>One extra boot in total, not one per test class</b>:
 * Spring caches a context per distinct configuration, so every class that imports this and
 * nothing else shares one context between them. A concurrency test that reached for a
 * {@code @MockitoBean} or a property override as well would be a configuration of its own
 * and would boot one more.
 */
public final class RowLockBarrier implements StatementInspector, HibernatePropertiesCustomizer {

	/** Reached only when a thread the barrier is waiting for never arrives, which is a failure. */
	private static final Duration BEFORE_GIVING_UP = Duration.ofSeconds(10);

	/** What {@link #holdTheOneThreadThatReachesARowLock} counts, and it counts them both. */
	private static final int THE_ONE_THREAD_AND_THE_RELEASE = 2;

	private static final String A_ROW_LOCK = "for update";

	private final AtomicReference<Arming> armed = new AtomicReference<>();

	/**
	 * Which arming each thread has already been held for, rather than a flag saying that it
	 * has. A thread outlives one race — a pooled one certainly does — and a flag it never
	 * clears would wave it straight through the next barrier, degrading a concurrency test to
	 * no synchronisation at all without failing.
	 */
	private final ThreadLocal<CountDownLatch> alreadyHeldFor = new ThreadLocal<>();

	@Override
	public void customize(Map<String, Object> hibernateProperties) {
		hibernateProperties.put(AvailableSettings.STATEMENT_INSPECTOR, this);
	}

	/**
	 * Arms the barrier for one race: the next {@code threads} threads to ask for a row lock
	 * are held until all of them have asked, and then released together.
	 *
	 * <p>Per thread rather than per statement — a thread already past the barrier runs
	 * unimpeded, which is what leaves it free to reach for its second lock and find out
	 * whether another thread is sitting on it.
	 */
	public void holdEachThreadAtItsFirstRowLock(int threads) {
		holdEachThreadAtItsFirstStatementNaming(A_ROW_LOCK, threads);
	}

	/**
	 * Arms the barrier at a statement of the test's choosing, for the races whose contending
	 * threads never reach for a row lock together.
	 *
	 * <p>Two retries of a failed Idempotency Key are that race. Both read the claim and both
	 * try to take it, and the guard on the update is what decides between them — but the read
	 * and the update are ordinary statements, so the loser routinely arrives after the winner
	 * has committed, reads a claim that is already taken and is turned away by a branch above
	 * the one under test. Named on the table instead, the barrier lines both threads up on the
	 * first statement either of them addresses to it.
	 *
	 * @param statementFragment matched case-insensitively against the SQL on its way to the
	 *                          driver; a table name is usually the honest choice, because it
	 *                          is what a test can name without also naming how Hibernate
	 *                          happens to spell the statement
	 */
	public void holdEachThreadAtItsFirstStatementNaming(String statementFragment, int threads) {
		armed.set(new Arming(statementFragment, new CountDownLatch(threads)));
	}

	/**
	 * Arms the barrier for the other shape of race — the one where only one of the contending
	 * threads ever reaches a row at all, so there is no second arrival to wait for and the
	 * test is what says when the held thread may go on.
	 *
	 * <p>{@code ConcurrentRequestsUnderOneKeyExecuteOnceTest} is that shape. Two requests
	 * carry one Idempotency Key; the database gives the key to one of them and the other is
	 * turned away by a constraint and two statements, never touching an Account. Counting
	 * arrivals there would hold the winner until a timeout rather than until the loser had
	 * been answered, which is the thing the test needs to observe.
	 *
	 * <p>Two arrivals because {@link #release} supplies the second. That is the whole
	 * difference between the two armings: above, the threads free each other; here, the one
	 * thread is freed by the test.
	 */
	public void holdTheOneThreadThatReachesARowLock() {
		holdEachThreadAtItsFirstRowLock(THE_ONE_THREAD_AND_THE_RELEASE);
	}

	/** Disarms the barrier, releasing anything still waiting on it. */
	public void release() {
		Arming arming = armed.getAndSet(null);
		if (arming != null) {
			while (arming.arrivals().getCount() > 0) {
				arming.arrivals().countDown();
			}
		}
	}

	@Override
	public String inspect(String sql) {
		Arming arming = armed.get();
		if (arming != null && arming.holdsBackA(sql) && alreadyHeldFor.get() != arming.arrivals()) {
			alreadyHeldFor.set(arming.arrivals());
			arming.arrivals().countDown();
			awaitTheOthers(arming.arrivals());
		}
		return sql;
	}

	/** One race the barrier is armed for: where it holds threads, and how many it is waiting for. */
	private record Arming(String statementFragment, CountDownLatch arrivals) {

		/**
		 * Lowercased once here, which is what makes the matching case-insensitive as
		 * documented: {@link #holdsBackA} lowercases only the statement, so a fragment
		 * armed in any other case would match nothing — silently, because a barrier that
		 * holds no thread still passes.
		 */
		Arming {
			statementFragment = statementFragment.toLowerCase();
		}

		boolean holdsBackA(String sql) {
			return sql.toLowerCase().contains(statementFragment);
		}
	}

	private static void awaitTheOthers(CountDownLatch arrivals) {
		try {
			arrivals.await(BEFORE_GIVING_UP.toSeconds(), TimeUnit.SECONDS);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
