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
 * concurrency claim that can fail.
 */
public final class RowLockBarrier implements StatementInspector, HibernatePropertiesCustomizer {

	/** Reached only when a thread the barrier is waiting for never arrives, which is a failure. */
	private static final Duration BEFORE_GIVING_UP = Duration.ofSeconds(10);

	private final AtomicReference<CountDownLatch> everyThreadsFirstRowLock = new AtomicReference<>();

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
		everyThreadsFirstRowLock.set(new CountDownLatch(threads));
	}

	/** Disarms the barrier, releasing anything still waiting on it. */
	public void release() {
		CountDownLatch arrivals = everyThreadsFirstRowLock.getAndSet(null);
		if (arrivals != null) {
			while (arrivals.getCount() > 0) {
				arrivals.countDown();
			}
		}
	}

	@Override
	public String inspect(String sql) {
		CountDownLatch arrivals = everyThreadsFirstRowLock.get();
		if (arrivals != null && isARowLock(sql) && alreadyHeldFor.get() != arrivals) {
			alreadyHeldFor.set(arrivals);
			arrivals.countDown();
			awaitTheOthers(arrivals);
		}
		return sql;
	}

	private static boolean isARowLock(String sql) {
		return sql.toLowerCase().contains("for update");
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
