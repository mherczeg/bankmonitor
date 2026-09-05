package hu.bankmonitor.payments.spike;

import hu.bankmonitor.testsupport.SpikeCurrency;
import hu.bankmonitor.testsupport.SpikeEmbeddableHost;
import hu.bankmonitor.testsupport.SpikeMoney;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ecosystem bet 1 of 2: <b>Hibernate maps a Java {@code record} as an
 * {@code @Embeddable}.</b>
 *
 * <p>Design decisions 1 and 16 make both {@code Account} and {@code Money} records. A
 * record has no no-arg constructor and no setters, so mapping one requires Hibernate to
 * instantiate it through its canonical constructor; if it could not, every entity's shape
 * would change. Cheap to settle now, expensive once six entities assume it.
 *
 * <p>The test also pins the <em>schema</em> the mapping produces, because ticket 02 turns
 * on {@code ddl-auto=validate} and from then on a migration that disagrees with Hibernate
 * by one column type fails startup.
 *
 * <p>If this test ever fails, the finding and its consequence belong in
 * {@code docs/deferred.md} rather than being routed around quietly.
 *
 * <p>Repository scanning is off for the reason
 * {@code MoneyMapsToTwoColumnsTest} spells out: {@code @EntityScan} does not narrow it, and
 * nothing here asks for a repository.
 */
@DataJpaTest(excludeAutoConfiguration = DataJpaRepositoriesAutoConfiguration.class)
@EntityScan(basePackageClasses = SpikeEmbeddableHost.class)
@TestPropertySource(properties = {
		// The spike owns its schema; from ticket 02 Flyway owns the real one and there
		// will never be a migration for this test-only table.
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
		flushAndDetachAll();

		SpikeEmbeddableHost reloaded = entityManager.find(SpikeEmbeddableHost.class, id);

		assertThat(reloaded.getBalance()).isEqualTo(balance);
		assertThat(reloaded.getBalance().minorUnits()).isEqualTo(123_45L);
		assertThat(reloaded.getBalance().currency()).isEqualTo(SpikeCurrency.EUR);
	}

	/**
	 * Reads past the mapping, in SQL, so this asserts what is in the database rather than
	 * what Hibernate is willing to say about it. Design decision 29 notes that the
	 * implicit naming strategy flattens {@code minorUnits} to {@code minor_units}
	 * silently, and the first migration has to use that name.
	 */
	@Test
	@DisplayName("the record's components become columns, named by the implicit strategy")
	void flattensRecordComponentsIntoColumns() {
		entityManager.persistAndFlush(new SpikeEmbeddableHost(new SpikeMoney(-7L, SpikeCurrency.HUF)));
		entityManager.clear();

		List<Object[]> rows = query("SELECT minor_units, currency FROM spike_embeddable_host");

		assertThat(rows).hasSize(1);
		assertThat(((Number) rows.getFirst()[0]).longValue()).isEqualTo(-7L);
		// The name, not the ordinal 2: @Enumerated(STRING) reached the field it annotates.
		assertThat(rows.getFirst()[1]).hasToString("HUF");
	}

	/**
	 * The surprising half of this spike, and the reason it earns its keep.
	 *
	 * <p>Hibernate 7 emits {@code currency enum ('EUR','HUF','USD')} on H2 — a
	 * <em>native</em> enum column — where older versions and most documentation lead you
	 * to expect {@code varchar}. Ticket 02 switches on {@code ddl-auto=validate}, so a
	 * migration writing {@code currency varchar(3)} looks obviously correct, matches every
	 * tutorial, and fails startup with a schema-validation error.
	 *
	 * <p>The remedy, if a plain {@code varchar} is wanted, is
	 * {@code @JdbcTypeCode(SqlTypes.VARCHAR)} on the component: the choice belongs in the
	 * entity, not in a workaround in the migration. See design decision 29.
	 */
	@Test
	@DisplayName("@Enumerated(STRING) becomes a native H2 ENUM column, not a varchar")
	void mapsEnumComponentToNativeEnumColumn() {
		List<Object[]> columnTypes = query("""
				SELECT data_type FROM information_schema.columns
				WHERE table_name = 'SPIKE_EMBEDDABLE_HOST' AND column_name = 'CURRENCY'
				""");

		assertThat(columnTypes).singleElement().hasToString("ENUM");
	}

	@SuppressWarnings("unchecked")
	private List<Object[]> query(String sql) {
		return entityManager.getEntityManager().createNativeQuery(sql).getResultList();
	}

	/** Detaches everything, so a subsequent read cannot be served from the first-level cache. */
	private void flushAndDetachAll() {
		entityManager.flush();
		entityManager.clear();
	}
}
