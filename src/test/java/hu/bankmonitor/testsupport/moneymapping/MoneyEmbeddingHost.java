package hu.bankmonitor.testsupport.moneymapping;

import hu.bankmonitor.payments.common.Money;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * Gives {@link Money} somewhere to be embedded, so its mapping can be checked against a
 * hand-written table before an entity in the domain depends on it.
 *
 * <p>The first entity to embed {@code Money} for real is the Account of ticket 08, which
 * ships the migration its columns have to match. Discovering the column types there means
 * discovering them while also writing the SQL that has to agree with them.
 *
 * <p>Like {@code testsupport.unmigrated}, this package holds exactly one entity, and that
 * is the constraint it exists for. Its test runs with {@code ddl-auto=validate} on, so
 * every entity the scan reaches needs a table behind it; a second entity here would fail
 * that test's startup rather than its assertions.
 */
@Entity
public class MoneyEmbeddingHost {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Embedded
	private Money amount;

	protected MoneyEmbeddingHost() {
		// required by JPA
	}

	public MoneyEmbeddingHost(Money amount) {
		this.amount = amount;
	}

	public Money getAmount() {
		return amount;
	}
}
