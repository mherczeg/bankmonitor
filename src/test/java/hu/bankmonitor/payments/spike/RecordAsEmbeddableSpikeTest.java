package hu.bankmonitor.payments.spike;

import hu.bankmonitor.testsupport.SpikeCurrency;
import hu.bankmonitor.testsupport.SpikeEmbeddableHost;
import hu.bankmonitor.testsupport.SpikeMoney;
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
 * Ecosystem bet 1 of 2: <b>Hibernate maps a Java {@code record} as an
 * {@code @Embeddable}.</b>
 *
 * <p>Design decision 16 makes Money a record, and design decision 1 makes Account one
 * too. If Hibernate could not instantiate a record through its canonical constructor,
 * every entity in the domain would need a different shape -- a change that is cheap now
 * and very expensive once six entities are written against the assumption. So this is
 * settled by a passing test rather than by reading a changelog.
 *
 * <p>The test also pins down the <em>schema</em> the mapping produces, because ticket 02
 * turns {@code ddl-auto=validate} on and from then on a hand-written migration that
 * disagrees with Hibernate by one column type fails startup. Knowing the answer now is
 * most of what this spike is for.
 *
 * <p>If this test ever fails, the finding and its consequence belong in
 * {@code docs/deferred.md} rather than being routed around quietly.
 */
@DataJpaTest
// Overrides Boot's default entity scan, which would start at the application's base
// package. Restricting it to the spike package keeps this throwaway entity out of every
// other JPA test in the suite.
@EntityScan(basePackageClasses = SpikeEmbeddableHost.class)
@TestPropertySource(properties = {
		// The spike owns its schema. From ticket 02 onwards Flyway owns the real one and
		// ddl-auto is `validate`; without these two lines this test would then fail
		// looking for a migration that will never exist for a test-only table.
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.flyway.enabled=false"
})
class RecordAsEmbeddableSpikeTest {

	@Autowired
	private TestEntityManager entityManager;

	@Test
	@DisplayName("a record survives a round trip through the database as an @Embeddable")
	void mapsRecordAsEmbeddable() {
		SpikeMoney balance = new SpikeMoney(123_45L, SpikeCurrency.EUR);

		Long id = entityManager.persistAndGetId(new SpikeEmbeddableHost(balance), Long.class);
		// Without the clear, `find` would hand back the very instance just persisted from
		// the first-level cache, and the test would prove nothing about reading.
		entityManager.flush();
		entityManager.clear();

		SpikeEmbeddableHost reloaded = entityManager.find(SpikeEmbeddableHost.class, id);

		// Hibernate had to call the canonical constructor to produce this: a record has
		// no no-arg constructor and no setters for it to use instead.
		assertThat(reloaded.getBalance()).isEqualTo(balance);
		assertThat(reloaded.getBalance().minorUnits()).isEqualTo(123_45L);
		assertThat(reloaded.getBalance().currency()).isEqualTo(SpikeCurrency.EUR);
	}

	@Test
	@DisplayName("the record's components become columns, named by the implicit strategy")
	void flattensRecordComponentsIntoColumns() {
		entityManager.persistAndFlush(new SpikeEmbeddableHost(new SpikeMoney(-7L, SpikeCurrency.HUF)));
		entityManager.clear();

		// Read past the mapping, in SQL, so this asserts what is actually in the database
		// rather than what Hibernate is willing to tell us about it. Design decision 29
		// notes that the implicit naming strategy turns `minorUnits` into `minor_units`
		// silently.
		@SuppressWarnings("unchecked")
		List<Object[]> rows = entityManager.getEntityManager()
				.createNativeQuery("SELECT minor_units, currency FROM spike_embeddable_host")
				.getResultList();

		assertThat(rows).hasSize(1);
		assertThat(((Number) rows.getFirst()[0]).longValue()).isEqualTo(-7L);
		// @Enumerated(STRING) on a record component reached the field: this is the name,
		// not the ordinal 0. Drop the annotation and this reads back as 2.
		assertThat(rows.getFirst()[1]).hasToString("HUF");
	}

	@Test
	@DisplayName("@Enumerated(STRING) becomes a native H2 ENUM column, not a varchar")
	void mapsEnumComponentToNativeEnumColumn() {
		// The trap this spike exists to catch, and the one that is genuinely surprising.
		// Hibernate 7 emits `currency enum ('EUR','HUF','USD')` on H2 -- a *native* enum
		// column -- where older versions and most documentation would lead you to expect
		// varchar. It matters because ticket 02 switches on ddl-auto=validate: a
		// migration that writes `currency varchar(3)` looks obviously correct, matches
		// every tutorial, and fails startup with a schema-validation error.
		//
		// The remedy, if a plain varchar is wanted instead, is an explicit
		// @JdbcTypeCode(SqlTypes.VARCHAR) on the component -- the choice has to be made
		// in the entity, not worked around in the migration.
		List<?> columnTypes = entityManager.getEntityManager()
				.createNativeQuery("""
						SELECT data_type FROM information_schema.columns
						WHERE table_name = 'SPIKE_EMBEDDABLE_HOST' AND column_name = 'CURRENCY'
						""")
				.getResultList();

		assertThat(columnTypes).singleElement().hasToString("ENUM");
	}
}
