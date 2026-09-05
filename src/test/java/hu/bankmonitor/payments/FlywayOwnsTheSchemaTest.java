package hu.bankmonitor.payments;

import hu.bankmonitor.testsupport.BootedApplicationTest;
import hu.bankmonitor.testsupport.unmigrated.UnmigratedEntityScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.contentOf;

/**
 * Flyway owns the schema and Hibernate only checks it, from the first commit onwards.
 *
 * <p>See design decision 29. There are no tables yet — each later slice ships the
 * migration for the table it introduces — so what is worth proving now is that the wiring
 * is live: Flyway really runs, and {@code validate} really refuses a schema that does not
 * match the entities.
 *
 * <p>It extends {@link BootedApplicationTest} for the booted application, not for the
 * HTTP client that comes with it. Any other {@code @SpringBootTest} annotation here would
 * be a second configuration and so a second cached context — a whole extra boot to reach
 * a database the suite has already started.
 */
class FlywayOwnsTheSchemaTest extends BootedApplicationTest {

	/** Both statements that put rows in a table, tolerating any whitespace between words. */
	private static final Pattern ROW_INSERTING_STATEMENT =
			Pattern.compile("\\b(insert|merge)\\s+into\\b", Pattern.CASE_INSENSITIVE);

	@Autowired
	private JdbcTemplate jdbc;

	/**
	 * Asks the database whether Flyway left its history table behind, rather than
	 * asserting {@code spring.flyway.enabled}, which would only restate the default. An
	 * empty migration directory still gets the table, so this holds before the first
	 * {@code V1__} file and keeps holding after it.
	 */
	@Test
	@DisplayName("Flyway runs at startup, even with no migrations to apply")
	void flywayRunsAtStartup() {
		Integer historyTables = jdbc.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				WHERE upper(table_name) = 'FLYWAY_SCHEMA_HISTORY'
				""", Integer.class);

		assertThat(historyTables).isEqualTo(1);
	}

	/**
	 * The reason {@code validate} is on from the first commit: a mismatch between an
	 * entity and the schema is a startup failure that names the object it went looking
	 * for, not a surprise at the first query.
	 */
	@Test
	@DisplayName("an entity with no table behind it fails startup, naming the missing table")
	void refusesToStartWhenAnEntityHasNoTable() {
		assertThatThrownBy(FlywayOwnsTheSchemaTest::bootScanningAnUnmigratedEntity)
				.hasStackTraceContaining("Schema validation: missing table [entity_with_no_table]");
	}

	/**
	 * Seed data in a migration runs everywhere the migration runs, the test suite
	 * included; demo accounts belong in a {@code @Profile("dev")} runner instead. Vacuous
	 * until the first migration lands, and load-bearing for every slice after that.
	 */
	@Test
	@DisplayName("no migration carries seed data")
	void migrationsCarryNoSeedData() throws IOException {
		Resource[] migrations = new PathMatchingResourcePatternResolver()
				.getResources("classpath*:db/migration/**/*.sql");

		for (Resource migration : migrations) {
			assertThat(contentOf(migration.getURL()))
					.as("%s", migration.getFilename())
					.doesNotContainPattern(ROW_INSERTING_STATEMENT);
		}
	}

	/**
	 * On its own database, not the shared {@code jdbc:h2:mem:payments} the surrounding
	 * context is still holding open: from the first migration onwards this second
	 * application would otherwise run Flyway against a live context's schema history.
	 */
	private static void bootScanningAnUnmigratedEntity() {
		new SpringApplicationBuilder(GlobalPaymentServiceApplication.class, UnmigratedEntityScan.class)
				.web(WebApplicationType.NONE)
				.properties("spring.datasource.url=jdbc:h2:mem:validate-refuses-unmigrated-entity")
				.run()
				.close();
	}
}
