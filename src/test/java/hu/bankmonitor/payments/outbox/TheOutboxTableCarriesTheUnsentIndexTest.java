package hu.bankmonitor.payments.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The index the poller's predicate needs is a shipped artefact rather than a claim in a
 * comment.
 *
 * <p>{@code findBySentAtIsNullOrderByIdAsc} is the poller's whole query, and the migration
 * answers it with {@code (sent_at, id)}: leading with {@code sent_at} groups the unsent rows,
 * trailing with {@code id} gives the ordering for free. Nothing about a correct system
 * depends on the index existing — every test in this slice would pass over a full scan of a
 * table with four rows in it — which is exactly why it needs a test of its own. The table
 * only grows, archival is deferred, and the run that notices its absence is one against a
 * table with a year of history in it.
 *
 * <p>So the assertions read the schema from {@code information_schema} rather than the
 * migration file. What is under test is what Flyway applied, not what the {@code .sql} says;
 * a migration renamed, re-ordered or never picked up fails here, and a second migration that
 * dropped the index would too.
 *
 * <p>H2 upper-cases unquoted identifiers, so every name below is asked for in upper case.
 * Startup is part of the assertion as well: {@code validate} has already compared
 * {@link OutboxEvent} against this table before any method runs.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class TheOutboxTableCarriesTheUnsentIndexTest {

	private static final String TABLE = "OUTBOX_EVENTS";

	private static final String UNSENT_INDEX = "OUTBOX_EVENTS_UNSENT";

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	/**
	 * In declaration order, because the poller reads the columns in one direction and
	 * {@code select *} in a test would agree with any of them.
	 */
	@Test
	@DisplayName("the unsent index leads with sent_at and trails with id")
	void theUnsentIndexLeadsWithSentAtAndTrailsWithId() {
		List<String> indexedColumns = jdbc().queryForList("""
				SELECT column_name FROM information_schema.index_columns
				WHERE table_schema = 'PUBLIC' AND table_name = ? AND index_name = ?
				ORDER BY ordinal_position
				""", String.class, TABLE, UNSENT_INDEX);

		assertThat(indexedColumns).containsExactly("SENT_AT", "ID");
	}

	@Test
	@DisplayName("the table carries the six columns an event is made of")
	void theTableCarriesTheSixColumnsAnEventIsMadeOf() {
		assertThat(columnsOf(TABLE)).containsExactly(
				"ID", "TRANSFER_ID", "EVENT_TYPE", "PAYLOAD", "OCCURRED_AT", "SENT_AT");
	}

	/**
	 * The nullability is the design, not an oversight: a null {@code sent_at} is how this
	 * table says an event has not been published, and it is the only column allowed to be
	 * absent. An event with no Transfer, no type, no payload or no instant is a row nobody
	 * could act on, and the columns refuse it rather than the poller discovering it.
	 */
	@Test
	@DisplayName("only sent_at may be null, because only sent_at means anything by being absent")
	void onlySentAtMayBeNull() {
		List<String> nullableColumns = jdbc().queryForList("""
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'PUBLIC' AND table_name = ? AND is_nullable = 'YES'
				ORDER BY ordinal_position
				""", String.class, TABLE);

		assertThat(nullableColumns).containsExactly("SENT_AT");
	}

	private List<String> columnsOf(String table) {
		return jdbc().queryForList("""
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'PUBLIC' AND table_name = ?
				ORDER BY ordinal_position
				""", String.class, table);
	}

	/**
	 * Built from the {@link DataSource} rather than injected, on
	 * {@code TheTableDecidesWhoHoldsAKeyTest}'s reasoning: a bean this test asked for would
	 * be one more thing its configuration depends on, and every variation in configuration
	 * is another cached application context (design decision 25).
	 */
	private JdbcTemplate jdbc() {
		if (jdbc == null) {
			jdbc = new JdbcTemplate(dataSource);
		}
		return jdbc;
	}
}
