package hu.bankmonitor.payments.outbox;

/**
 * How an Outbox Event leaves this application, and the one thing that changes when the
 * transport does.
 *
 * <p><b>Swapping a broker in happens under this method, and replaces nothing above it.</b>
 * "Commit to the database, then send to Kafka" is still two writes with no transaction
 * spanning them; the outbox exists because a broker publish cannot join a database
 * transaction, so Kafka is the transport under {@code publish} and not an alternative to
 * the table. Design decision 12 argues it, and {@code docs/deferred.md} names Kafka and
 * Spring Modulith's Event Publication Registry as the production answers.
 *
 * <p><b>The one caller is the poller</b>, which is package-private. That is deliberate: the
 * only way to reach this method with an event is to have written the event down first, so
 * "published but not committed" is not a state any caller can produce.
 *
 * <p><b>An implementation must not mark the event sent</b>, and cannot — {@link OutboxEvent}
 * has no mutator and the row is the poller's to update afterwards. A transport that recorded
 * its own success would record it for a publish that half happened, and the failure this
 * design accepts is a duplicate rather than a loss.
 *
 * <p>Failure is reported by throwing. The event stays unsent and the next run tries again,
 * which is the whole of the retry policy: backoff, a dead-letter path and ordering between
 * two events on one Transfer are deferred, with reasoning, in {@code docs/deferred.md}.
 */
public interface EventPublisher {

	/**
	 * Delivers one event to the outside world.
	 *
	 * <p>Delivery is <b>at-least-once</b>, deliberately. An implementation may be called
	 * again with an event it has already delivered — after a publish that succeeded and a
	 * mark that did not commit, or after a restart — so consumers deduplicate. They must
	 * anyway: exactly-once across a network is not something a retry can buy.
	 *
	 * @throws RuntimeException if the event could not be delivered, which leaves the row
	 *         unsent for the next run
	 */
	void publish(OutboxEvent event);
}
