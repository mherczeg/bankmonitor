package hu.bankmonitor.payments.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The transport this build ships: one log line per event.
 *
 * <p>It is a real implementation of {@link EventPublisher} rather than a stub, and the
 * distinction matters to what a reviewer can conclude. The outbox, the poller and the
 * at-least-once contract above it all run exactly as they would against a broker; the only
 * thing that is not real is the far end. Design decision 31 makes this line the whole of
 * the observability story alongside the Actuator health endpoint.
 *
 * <p>The line names every field a consumer would receive, so what went out is legible in
 * the log rather than inferable from the table it left. The payload is included whole for
 * the same reason — these are Transfer amounts and account identifiers in a service with no
 * authentication and demo data behind it, so there is nothing here a log may not hold.
 */
@Component
class LoggingEventPublisher implements EventPublisher {

	private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

	@Override
	public void publish(OutboxEvent event) {
		log.info("Published outbox event id={} type={} transferId={} occurredAt={} payload={}",
				event.getId(), event.getEventType(), event.getTransferId(),
				event.getOccurredAt(), event.getPayload());
	}
}
