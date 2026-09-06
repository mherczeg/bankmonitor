package hu.bankmonitor.payments.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Where an Outbox Event is written, and the second thing this slice makes public.
 *
 * <p>It is not a port. Design decision 30 counts three of those and this is none of them —
 * there is one implementation, no seam and nothing to swap. {@link EventPublisher} is the
 * port, and it faces the other way: this is how a change records what it did, that is how
 * the record leaves the building. The slice's line in design decision 30 named only the
 * port because the port is the interesting half; a caller in {@code transfers} still needs
 * something it can name, and ticket 07 has the precedent of a slice exporting a second
 * public type without exporting a second port.
 *
 * <p><b>{@code MANDATORY} is the guarantee, not a precaution.</b> The whole claim of a
 * transactional outbox is that the event and the change it describes commit together, and a
 * recorder that quietly opened a transaction of its own when there was none would satisfy
 * every test that reads the row back while breaking the one property it exists for. Refusing
 * to run outside a transaction is how that claim is enforced rather than documented —
 * {@code CheckLedger.openFor} and {@code AccountLocking.lockForTransfer} take the same
 * propagation for the same kind of reason.
 *
 * <p>The method is public on a package-private-by-default design because
 * {@code @Transactional} on a non-public method is silently ignored under proxy-based AOP —
 * the trap {@code hu.bankmonitor.payments} documents. The class is public for the ordinary
 * reason: {@code transfers} has to name it.
 */
@Component
public class OutboxEventRecorder {

	private final OutboxEventRepository events;

	private final ObjectMapper json;

	private final Clock clock;

	OutboxEventRecorder(OutboxEventRepository events, ObjectMapper json, Clock clock) {
		this.events = events;
		this.json = json;
		this.clock = clock;
	}

	/**
	 * Records that something happened to a Transfer, in the transaction that made it happen.
	 *
	 * <p>The payload is serialised here rather than by the caller because this slice owns the
	 * column: one place decides that an event's payload is JSON, so no caller can write a
	 * shape the poller then hands on. It is an {@code Object} because the outbox does not
	 * know the vocabulary it carries — the same reason {@code event_type} has no check
	 * constraint behind it.
	 *
	 * <p>A payload that cannot be serialised throws, and the change rolls back with it. That
	 * is the right way round: a settlement that committed without its event is exactly the
	 * disagreement this table exists to prevent, so the write that cannot be described is the
	 * one that must not happen.
	 *
	 * <p>The mapper is Jackson 3's — {@code tools.jackson.databind}, the one Spring Boot 4
	 * auto-configures. Jackson 2 is on the classpath as well and its {@code ObjectMapper} has
	 * the same simple name, so asking for the wrong one compiles and then fails at startup
	 * with an unsatisfied dependency. Its serialisation failures are unchecked, which is why
	 * there is no wrapping here.
	 *
	 * @throws org.springframework.transaction.IllegalTransactionStateException if called
	 *         outside the transaction of the change being described
	 * @throws tools.jackson.core.JacksonException if the payload cannot be written as JSON
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(Long transferId, String eventType, Object payload) {
		events.save(new OutboxEvent(transferId, eventType, json.writeValueAsString(payload), clock.instant()));
	}
}
