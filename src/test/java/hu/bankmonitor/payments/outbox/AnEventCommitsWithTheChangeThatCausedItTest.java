package hu.bankmonitor.payments.outbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An Outbox Event exists exactly when the change that caused it exists, which is the one
 * property this whole slice is built to have.
 *
 * <p>The interesting assertion is the negative one. A recorder that wrote the row is easy
 * and every design gets that right; what separates a transactional outbox from "commit,
 * then send" is that a transaction which rolls back leaves <em>nothing</em> for the poller
 * to find. So the rollback case below is the criterion, and the row-reaches-the-table case
 * is only what makes it meaningful.
 *
 * <p><b>{@code MANDATORY} is tested alongside it, and the pair has to be read together.</b>
 * A recorder that opened a transaction of its own when there was none would pass a rollback
 * test that never opened one — the row would commit on its own and the assertion would be
 * about nothing. Refusing to run outside a caller's transaction is what makes the rollback
 * case say what it appears to say.
 *
 * <p>The test is deliberately <b>not</b> transactional, on
 * {@code TheTableDecidesWhoHoldsAKeyTest}'s reasoning: {@code @DataJpaTest} would otherwise
 * wrap each method in a transaction and roll it back, so a committed row and a rolled-back
 * one would look identical. Rows therefore outlive the method that wrote them and
 * {@link #dropEveryEvent()} clears them by hand.
 *
 * <p>Nothing here has to race a poller. {@code @DataJpaTest} does not component-scan user
 * {@code @Configuration}, so {@code SchedulingConfiguration} never loads in this slice,
 * {@code @Scheduled} is never read, and the only actor is the test method.
 *
 * <p>Rows come back in SQL rather than through the entity, so what is asserted is what
 * reached the database — a round trip through the mapping under test would agree with
 * itself whatever it wrote.
 */
@DataJpaTest
@Import({OutboxEventRecorder.class, AnEventCommitsWithTheChangeThatCausedItTest.Fixtures.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties =
		// The application's own setting, restated because the migration is half of every
		// assertion below: `validate` has compared the entity against V5 before any run.
		"spring.jpa.hibernate.ddl-auto=validate")
class AnEventCommitsWithTheChangeThatCausedItTest {

	static final Instant RECORDED_AT = Instant.parse("2026-09-05T10:15:30Z");

	private static final long TRANSFER_ID = 77L;

	private static final String EVENT_TYPE = "TRANSFER_SETTLED";

	private static final SettledTransfer SETTLEMENT = new SettledTransfer(TRANSFER_ID, 150_00L);

	private static final String SETTLEMENT_AS_JSON = "{\"transferId\":77,\"amountMinorUnits\":15000}";

	@Autowired
	private OutboxEventRecorder recorder;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private JdbcTemplate jdbc;

	private TransactionTemplate change;

	@AfterEach
	void dropEveryEvent() {
		jdbc().update("DELETE FROM outbox_events");
	}

	@Test
	@DisplayName("a recorded event reaches the table with its payload as JSON and nothing sent")
	void aRecordedEventReachesTheTable() {
		change().executeWithoutResult(status -> recorder.record(TRANSFER_ID, EVENT_TYPE, SETTLEMENT));

		Map<String, Object> event = theOnlyEvent();

		assertThat(event.get("TRANSFER_ID")).isEqualTo(TRANSFER_ID);
		assertThat(event.get("EVENT_TYPE")).isEqualTo(EVENT_TYPE);
		assertThat(event.get("PAYLOAD")).isEqualTo(SETTLEMENT_AS_JSON);
		assertThat(instantOf(event.get("OCCURRED_AT"))).isEqualTo(RECORDED_AT);
		assertThat(event.get("SENT_AT")).isNull();
	}

	/**
	 * The criterion, and the reason the table exists: a change that did not happen leaves no
	 * news of itself behind. The row and the money movement are one write, so the failure
	 * mode a broker call would have — a settlement rolled back and an event already gone out
	 * — is not reachable from here.
	 */
	@Test
	@DisplayName("an event recorded in a transaction that rolls back is never written at all")
	void anEventRecordedInATransactionThatRollsBackIsNeverWritten() {
		assertThatThrownBy(() -> change().executeWithoutResult(status -> {
			recorder.record(TRANSFER_ID, EVENT_TYPE, SETTLEMENT);
			throw new IllegalStateException("the change this event describes could not be made");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(everyEvent()).isEmpty();
	}

	/**
	 * What {@code MANDATORY} buys, asserted as a scenario rather than as the annotation.
	 * Without it a caller outside a transaction would get a row committed on its own — which
	 * is the "commit, then send" the outbox exists to replace, and which no assertion about
	 * a rollback would notice.
	 */
	@Test
	@DisplayName("recording outside any transaction is refused rather than committed alone")
	void recordingOutsideAnyTransactionIsRefused() {
		assertThatThrownBy(() -> recorder.record(TRANSFER_ID, EVENT_TYPE, SETTLEMENT))
				.isInstanceOf(IllegalTransactionStateException.class);

		assertThat(everyEvent()).isEmpty();
	}

	/**
	 * A payload nobody can describe takes the change down with it, which is the right way
	 * round: a settlement that committed without its event is exactly the disagreement this
	 * table prevents, so the write that cannot be described is the one that must not happen.
	 */
	@Test
	@DisplayName("a payload that cannot be serialised rolls the change back with it")
	void aPayloadThatCannotBeSerialisedRollsTheChangeBack() {
		assertThatThrownBy(() -> change().executeWithoutResult(
				status -> recorder.record(TRANSFER_ID, EVENT_TYPE, new UndescribablePayload())))
				.rootCause()
				.hasMessage(UndescribablePayload.REFUSAL);

		assertThat(everyEvent()).isEmpty();
	}

	private Map<String, Object> theOnlyEvent() {
		List<Map<String, Object>> events = everyEvent();
		assertThat(events).hasSize(1);
		return events.getFirst();
	}

	private List<Map<String, Object>> everyEvent() {
		return jdbc().queryForList("SELECT * FROM outbox_events ORDER BY id");
	}

	/** H2 hands a {@code timestamp with time zone} back as an {@link OffsetDateTime}. */
	private static Instant instantOf(Object column) {
		return ((OffsetDateTime) column).toInstant();
	}

	/**
	 * Built from the {@link DataSource} rather than injected, because a {@code JdbcTemplate}
	 * the slice happened to expose would be one more thing this test's configuration depends
	 * on — and every variation in configuration is another cached application context
	 * (design decision 25).
	 */
	private JdbcTemplate jdbc() {
		if (jdbc == null) {
			jdbc = new JdbcTemplate(dataSource);
		}
		return jdbc;
	}

	/** Stands in for the money movement: the transaction an event is recorded inside of. */
	private TransactionTemplate change() {
		if (change == null) {
			change = new TransactionTemplate(transactionManager);
		}
		return change;
	}

	/** What a Transfer's settlement would put in the payload, in miniature. */
	record SettledTransfer(long transferId, long amountMinorUnits) {
	}

	/** A payload Jackson meets and cannot write, which is a failure the caller has to see. */
	static final class UndescribablePayload {

		static final String REFUSAL = "this payload cannot be written as JSON";

		public String getDetail() {
			throw new IllegalStateException(REFUSAL);
		}
	}

	/**
	 * The two beans the recorder cannot do without and this slice does not otherwise have.
	 *
	 * <p><b>The clock is a second one, deliberately.</b> {@code @DataJpaTest} filters
	 * component scanning but still loads the {@code @SpringBootConfiguration} class itself,
	 * so the application's own {@code systemUTC} {@code @Bean} is already here — a method
	 * named {@code clock()} would be a bean definition override and fail the context outright.
	 * A differently named bean marked {@code @Primary} is what an injection point gets
	 * instead, and it is a clock a test can hold still.
	 *
	 * <p>The mapper is Jackson 3's — {@code tools.jackson.databind}, the one Spring Boot 4
	 * auto-configures. Jackson 2 is on the classpath too and its {@code ObjectMapper} has the
	 * same simple name, so importing the wrong one compiles and then fails to wire.
	 */
	@TestConfiguration
	static class Fixtures {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(RECORDED_AT, ZoneOffset.UTC);
		}

		@Bean
		ObjectMapper json() {
			return JsonMapper.builder().build();
		}
	}
}
