package hu.bankmonitor.testsupport.unmigrated;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * An entity that no migration will ever create a table for.
 *
 * <p>It exists so a test can boot the application with it on the entity scan and watch
 * {@code ddl-auto=validate} refuse to start. That is the point of ticket 02: a mismatch
 * between an entity and the schema surfaces at startup, naming the object Hibernate went
 * looking for, rather than at the first query.
 *
 * <p>Every word of the name is a separate word to Hibernate's implicit naming strategy,
 * which is what makes the expected {@code entity_with_no_table} readable. An earlier
 * {@code EntityWithoutAMigration} became {@code entity_withoutamigration} — the strategy
 * does not split a one-letter word off the one after it.
 */
@Entity
public class EntityWithNoTable {

	@Id
	private Long id;

	protected EntityWithNoTable() {
		// required by JPA
	}
}
