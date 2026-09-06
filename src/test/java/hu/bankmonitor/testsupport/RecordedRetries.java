package hu.bankmonitor.testsupport;

import org.springframework.context.event.EventListener;
import org.springframework.resilience.retry.MethodRetryEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What Spring Framework 7's retry machinery says it did, so a test can assert resilience
 * rather than claim it.
 *
 * <p>The alternative is counting requests at the mock server, which proves a number of
 * calls were made but not that the retry policy is what made them — a loop in the client
 * would look identical. This listens to the framework's own event instead, which exists
 * for exactly this and needs nothing mocked.
 *
 * <p>The list is copy-on-write because the events are published on whichever thread the
 * retried call is running on, which is not always the test's.
 */
public class RecordedRetries {

	private final List<MethodRetryEvent> events = new CopyOnWriteArrayList<>();

	@EventListener
	void record(MethodRetryEvent event) {
		events.add(event);
	}

	/**
	 * How many calls to the provider failed. One event is published per failed attempt,
	 * the first one included, so a value of two means the third call is the one that
	 * worked.
	 */
	public long failedAttempts() {
		return events.stream().filter(event -> !event.isRetryAborted()).count();
	}

	/** Whether the retry budget ran out, which the framework announces with its own event. */
	public boolean gaveUp() {
		return events.stream().anyMatch(MethodRetryEvent::isRetryAborted);
	}

	public void forget() {
		events.clear();
	}
}
