package hu.bankmonitor.testsupport;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * Throwaway entity that exists only to give {@link SpikeMoney} somewhere to be embedded.
 *
 * <p>Named for what it does rather than for anything in the domain. Calling it an Account
 * or a Holding would imply it is a first draft of a real entity, and CONTEXT.md is
 * deliberate about which words this codebase uses -- "Holding" is not one of them.
 *
 * <p>It lives outside {@code hu.bankmonitor.payments} on purpose. Boot's default entity
 * scan starts at the application's base package and test classes share the runtime
 * classpath, so an entity placed under it would join every JPA test in the suite. That
 * matters more from ticket 02 onwards: once Flyway owns the schema and
 * {@code ddl-auto=validate} is on, a stray entity with no migration behind it fails
 * startup for every test that touches the database.
 */
@Entity
public class SpikeEmbeddableHost {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Embedded
	private SpikeMoney balance;

	protected SpikeEmbeddableHost() {
		// required by JPA
	}

	public SpikeEmbeddableHost(SpikeMoney balance) {
		this.balance = balance;
	}

	public Long getId() {
		return id;
	}

	public SpikeMoney getBalance() {
		return balance;
	}
}
