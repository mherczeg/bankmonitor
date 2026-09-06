package hu.bankmonitor.payments.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * What drains the outbox: every unsent event, published and then marked sent.
 *
 * <p>It is package-private, which is design decision 30's line for this slice. Nothing
 * outside calls a poller — it is woken by the scheduler, and the only reason to reach it
 * from elsewhere would be to make it run, which is a thing tests in this package can do and
 * production has no use for.
 *
 * <p><b>Publish first, mark second, and neither inside the other's transaction.</b> That
 * order is the whole of the at-least-once contract. Marking first would make delivery
 * at-most-once: a publish that then failed would leave a row saying it had gone out. Doing
 * both in one transaction would hold it open across the publish — a log line today, a broker
 * round trip once the transport is swapped — which is the shape {@code AccountLocking} exists
 * to keep off the locked path, and would still not make the pair atomic, because a broker
 * cannot join a transaction. So the failure this design accepts is a duplicate, and
 * consumers deduplicate, which they must anyway.
 *
 * <p><b>A publish that throws costs its own row and no other.</b> The run logs it, leaves it
 * unsent and carries on: stopping at the first failure would let one undeliverable event hold
 * up every event behind it, and there is no ordering guarantee here for it to be protecting.
 * Retrying it immediately on the next run is the entire retry policy — backoff and a
 * dead-letter path for a row that can never be delivered are deferred, with reasoning, in
 * {@code docs/deferred.md}.
 *
 * <p><b>One instance is assumed.</b> Two would read the same unsent rows and publish them
 * twice; the fix is {@code SELECT … FOR UPDATE SKIP LOCKED} on the poll query, and it is one
 * of the two named breaks of the single-instance assumption in {@code docs/deferred.md}. The
 * guard on {@link OutboxEventRepository#markSent} narrows what that costs but does not close
 * it — it stops a second timestamp being written, not a second publish.
 */
@Component
class OutboxPoller {

	/**
	 * How many events one run takes.
	 *
	 * <p>A cap rather than everything unsent, because archival is deferred and the table only
	 * grows: an unbounded read would eventually pull the history of the system into a
	 * scheduled method. Nothing is lost by the cap — what it leaves behind is still unsent,
	 * and the next run is one delay away.
	 */
	private static final Limit ONE_RUN = Limit.of(100);

	private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

	private final OutboxEventRepository events;

	private final EventPublisher publisher;

	private final Clock clock;

	OutboxPoller(OutboxEventRepository events, EventPublisher publisher, Clock clock) {
		this.events = events;
		this.publisher = publisher;
		this.clock = clock;
	}

	/**
	 * Publishes what has not gone out yet, and records that it has.
	 *
	 * <p>The method is public so that no proxy strategy can decide it is not worth advising,
	 * on the reasoning {@code hu.bankmonitor.payments} documents for {@code @Transactional}.
	 * The class it sits on is package-private, so this widens nothing.
	 *
	 * <p>{@code fixedDelay} rather than {@code fixedRate}: the delay is measured from the end
	 * of the previous run, so runs cannot overlap and a slow batch cannot have a second one
	 * publishing the rows it is still working through.
	 */
	@Scheduled(fixedDelayString = "${payments.outbox.poll-delay}")
	public void publishUnsentEvents() {
		for (OutboxEvent event : events.findBySentAtIsNullOrderByIdAsc(ONE_RUN)) {
			if (published(event)) {
				events.markSent(event.getId(), clock.instant());
			}
		}
	}

	private boolean published(OutboxEvent event) {
		try {
			publisher.publish(event);
			return true;
		}
		catch (RuntimeException failure) {
			log.error("Outbox event id={} could not be published and stays unsent", event.getId(), failure);
			return false;
		}
	}
}
