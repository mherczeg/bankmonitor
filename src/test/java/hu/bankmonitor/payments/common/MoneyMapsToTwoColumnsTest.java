package hu.bankmonitor.payments.common;

import hu.bankmonitor.testsupport.moneymapping.MoneyEmbeddingHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One {@link Money} flattens to two columns, and the currency is a {@code varchar} holding
 * the currency's <em>name</em> rather than its ordinal.
 *
 * <p>The name is not the default: an enum JPA is given no instruction about is stored by
 * <b>ordinal</b>, so reordering {@link Currency}'s constants would silently reinterpret
 * every row already written. What this test pins is that {@code Money} carries an
 * instruction at all — strip both of its currency annotations and the context fails to
 * start, because Hibernate then expects a {@code tinyint} where the migration wrote a
 * {@code varchar}. Design decision 29 has the full mapping story and why both annotations
 * stay.
 *
 * <p>The arrangement is the application's own rather than a JPA test's usual one: the table
 * comes from a migration and Hibernate runs on {@code validate}, exactly as ticket 08's
 * accounts table will. Checking the mapping <em>against hand-written SQL</em> is what makes
 * a startup failure the assertion, and it rehearses the position ticket 08 writes its
 * migration from.
 */
@DataJpaTest
@EntityScan(basePackageClasses = MoneyEmbeddingHost.class)
@TestPropertySource(properties = {
		// The host entity is test-only, so its table ships beside this test rather than
		// in db/migration, which Flyway owns for the schema the application runs on.
		"spring.flyway.locations=classpath:db/testmigration/moneymapping",
		// The application's own setting, restated because the claim below rests on it.
		"spring.jpa.hibernate.ddl-auto=validate"
})
class MoneyMapsToTwoColumnsTest {

	@Autowired
	private TestEntityManager entityManager;

	/**
	 * Startup is half the assertion: {@code validate} has already compared the mapping
	 * against the migration by the time this method runs. The body confirms the agreement
	 * is a working one and not merely a nominal one.
	 */
	@Test
	@DisplayName("the mapping agrees with a hand-written varchar column, and money round trips through it")
	void roundTripsThroughAHandWrittenSchema() {
		Money amount = new Money(123_45L, Currency.EUR);

		Long id = entityManager.persistAndGetId(new MoneyEmbeddingHost(amount), Long.class);
		entityManager.flush();
		entityManager.clear();

		assertThat(entityManager.find(MoneyEmbeddingHost.class, id).getAmount()).isEqualTo(amount);
	}

	/**
	 * Read in SQL rather than through Hibernate, so this asserts what is in the database
	 * rather than what the mapping is willing to say about it — a round trip through the
	 * same mapping would agree with itself whatever it wrote.
	 *
	 * <p>Note what the implicit naming strategy does <em>not</em> do — the host's field is
	 * called {@code amount} and no column is named after it. An entity carrying two Money
	 * figures therefore needs {@code @AttributeOverride} to keep them apart, which is
	 * ticket 08's first job.
	 */
	@Test
	@DisplayName("the count and the currency are stored as minor_units and a currency name")
	void storesTheCountAndTheCurrencyName() {
		entityManager.persistAndFlush(new MoneyEmbeddingHost(new Money(-150L, Currency.HUF)));
		entityManager.clear();

		@SuppressWarnings("unchecked")
		List<Object[]> rows = entityManager.getEntityManager()
				.createNativeQuery("SELECT minor_units, currency FROM money_embedding_host")
				.getResultList();

		assertThat(rows).hasSize(1);
		assertThat(((Number) rows.getFirst()[0]).longValue()).isEqualTo(-150L);
		assertThat(rows.getFirst()[1]).hasToString("HUF");
	}
}
