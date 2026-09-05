package hu.bankmonitor.payments;

import hu.bankmonitor.testsupport.BootedApplicationTest;
import hu.bankmonitor.testsupport.unmigrated.UnmigratedEntityScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
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
 * <p>See design decision 29. Each slice ships the migration for the table it introduces,
 * so what is proved here is that the wiring is live — Flyway really runs, and
 * {@code validate} really refuses a schema that does not match the entities — and that
 * <b>seed data stays out of it</b>, neither smuggled into a migration nor written by a
 * runner that starts outside the profile meant to carry it.
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

	@Autowired
	private ApplicationContext context;

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
	 * The other half of "seed data is not schema", and the half a migration check cannot
	 * see: a runner that writes demo rows on the way up is not in a {@code .sql} file at
	 * all. This context has no profile active, which is what the whole suite runs under, so
	 * a seeder reaching it would start every database test from someone else's fixtures.
	 *
	 * <p>Deliberately about the runner types rather than about {@code DemoAccountSeeder} by
	 * name: the next slice tempted to seed something will reach for the same two
	 * interfaces, and this holds it to the same profile guard. It is also the only form of
	 * the assertion that survives {@code AccountListingTest} writing rows into this same
	 * shared context.
	 */
	@Test
	@DisplayName("no startup runner seeds the database outside the dev profile")
	void noStartupRunnerSeedsTheDatabaseOutsideTheDevProfile() {
		assertThat(context.getBeanNamesForType(CommandLineRunner.class)).isEmpty();
		assertThat(context.getBeanNamesForType(ApplicationRunner.class)).isEmpty();
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
