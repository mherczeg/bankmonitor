package hu.bankmonitor.payments.transfers.checks;

import hu.bankmonitor.payments.common.Money;
import hu.bankmonitor.payments.transfers.Transfer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static hu.bankmonitor.payments.common.Currency.EUR;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one way the ledger's own writer could produce the state the whole design is arranged
 * to prevent.
 *
 * <p>{@code NothingButTheReservationCreatesATransferTest} holds that no <em>second</em>
 * writer can leave a {@code PENDING} Transfer with no ledger, and {@code
 * EveryTransferOpensItsCheckLedgerTest} holds that the one writer opens one. Neither says
 * anything about this writer degenerating: {@link CheckLedger#openFor} writes one row per
 * Check the policy names, so a policy that names none makes it write nothing at all, and
 * every test in both classes stays green while the Transfer it just let through is stuck.
 *
 * <p>The complement to it is at the other end — {@link LedgerDecision#decide} refuses an
 * empty ledger — and that is the wrong end to defend alone, because by the time {@code
 * decide} is handed one the Transfer holding an operator's funds already exists.
 *
 * <p>No Spring context: {@link CheckPolicy} is a class with one overridable method and
 * {@link CheckLedgerRepository} declares exactly one, so the two collaborators are a
 * subclass and a lambda.
 */
class CheckLedgerTest {

	@Test
	@DisplayName("a policy that requires no Checks is refused, rather than opening an empty ledger")
	void refusesToOpenALedgerWithNoChecksInIt() {
		CheckLedger ledger = new CheckLedger(requiringNothing(), noRowMayBeWritten());

		assertThatThrownBy(() -> ledger.openFor(written()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("never empty");
	}

	private static CheckPolicy requiringNothing() {
		return new CheckPolicy() {
			@Override
			Set<Check> requiredFor(Transfer transfer) {
				return Set.of();
			}
		};
	}

	private static CheckLedgerRepository noRowMayBeWritten() {
		return entry -> {
			throw new AssertionError("a refused ledger writes no rows at all");
		};
	}

	/**
	 * A Transfer that has been written, which is what {@code openFor} requires and what a
	 * generated identifier makes awkward to hand it. Overriding the getter says the one thing
	 * about the Transfer this test depends on and nothing else.
	 */
	private static Transfer written() {
		Money euro = new Money(10_00L, EUR);
		return new Transfer(1L, 2L, euro, euro, Instant.parse("2026-09-05T10:15:30Z")) {
			@Override
			public Long getId() {
				return 1L;
			}
		};
	}
}
