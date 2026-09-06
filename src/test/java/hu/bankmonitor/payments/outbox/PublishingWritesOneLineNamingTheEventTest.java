package hu.bankmonitor.payments.outbox;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transport this build ships delivers an event by naming all of it, once.
 *
 * <p>The log line is not a debugging aid here — it <em>is</em> the far end of
 * {@link EventPublisher}, so what it carries is what a consumer would have received. A line
 * that said only "published event 4" would leave the outbox's whole payload argument
 * unobservable: design decision 11 rejected polling so that a consumer would not have to
 * call back for the amount, and a transport that dropped the payload would reintroduce
 * exactly that. So every field is asserted, and one line rather than several, because a
 * publish is one delivery.
 *
 * <p>No Spring context: the class under test has one collaborator, and it is the logging
 * framework. The appender is attached to this publisher's own logger rather than to the root
 * so that nothing else in the suite's output can satisfy the assertions.
 *
 * <p>The event is unpersisted, so its identifier is null — {@link OutboxEvent} takes its id
 * from the database and the constructor cannot invent one. That is the honest shape of the
 * object at this seam only in a test; in production the poller has read the row back, so the
 * assertion below is about the four fields the constructor does set.
 */
class PublishingWritesOneLineNamingTheEventTest {

	private static final long TRANSFER_ID = 77L;

	private static final String EVENT_TYPE = "TRANSFER_SETTLED";

	private static final String PAYLOAD = "{\"transferId\":77,\"amountMinorUnits\":15000}";

	private static final Instant OCCURRED_AT = Instant.parse("2026-09-05T10:15:30Z");

	private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

	private final ch.qos.logback.classic.Logger publisherLog =
			(ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LoggingEventPublisher.class);

	@BeforeEach
	void listenToThePublishersLogger() {
		lines.start();
		publisherLog.addAppender(lines);
	}

	@AfterEach
	void stopListening() {
		publisherLog.detachAppender(lines);
		lines.stop();
	}

	@Test
	@DisplayName("publishing one event writes one info line carrying every field of it")
	void publishingOneEventWritesOneInfoLineCarryingEveryFieldOfIt() {
		new LoggingEventPublisher().publish(
				new OutboxEvent(TRANSFER_ID, EVENT_TYPE, PAYLOAD, OCCURRED_AT));

		assertThat(lines.list).hasSize(1);
		ILoggingEvent line = lines.list.getFirst();
		assertThat(line.getLevel()).isEqualTo(Level.INFO);
		assertThat(line.getFormattedMessage()).contains(
				EVENT_TYPE, String.valueOf(TRANSFER_ID), PAYLOAD, OCCURRED_AT.toString());
	}

	/**
	 * A publisher that logged nothing, or that logged a template with its placeholders
	 * unfilled, would still leave a line for the assertion above to count. What rules that
	 * out is that the formatted message is what carries the values, so the raw template is
	 * asserted <em>not</em> to.
	 */
	@Test
	@DisplayName("the values are in the line rather than left as placeholders in the template")
	void theValuesAreInTheLineRatherThanLeftAsPlaceholders() {
		new LoggingEventPublisher().publish(
				new OutboxEvent(TRANSFER_ID, EVENT_TYPE, PAYLOAD, OCCURRED_AT));

		ILoggingEvent line = lines.list.getFirst();

		assertThat(line.getMessage()).doesNotContain(EVENT_TYPE, PAYLOAD);
		assertThat(line.getFormattedMessage()).doesNotContain("{}");
	}
}
