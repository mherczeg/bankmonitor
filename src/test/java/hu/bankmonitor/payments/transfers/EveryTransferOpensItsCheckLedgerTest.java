package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.accounts.InsufficientFundsException;
import hu.bankmonitor.payments.common.Money;
import hu.bankmonitor.payments.transfers.checks.CheckLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;

import static hu.bankmonitor.payments.common.Currency.EUR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That a Transfer and the Check Ledger it will be settled against are written together, or
 * neither is.
 *
 * <p>The failure this is aimed at is quiet rather than loud. A ledger opened <em>after</em>
 * the Transfer's transaction commits, or skipped on some second path that creates a
 * Transfer, leaves a {@code PENDING} row with an empty ledger — and an empty ledger has no
 * outstanding Check to answer, so nothing settles it, nothing rejects it, and the support
 * question "why is this stuck" has no answer to find. The funds stay reserved until a person
 * notices.
 *
 * <p>Two of the three claims here are structural rather than behavioural: {@link
 * CheckLedger#openFor} refuses to run outside a transaction, and {@code
 * NothingButTheReservationCreatesATransferTest} holds that there is no second path. What is
 * left for a booted test is that the one path writes what it should.
 *
 * <p>Rows come back in SQL rather than through the entities for {@link TransferScenario}'s
 * reason: a round trip through the mapping under test would agree with itself whatever it
 * wrote.
 */
class EveryTransferOpensItsCheckLedgerTest extends TransferScenario {

	private static final long SOURCE = 1L;
	private static final long DESTINATION = 2L;
	private static final long OPENING_BALANCE = 250_00L;

	@Autowired
	private CheckLedger ledger;

	@BeforeEach
	void openBothAccounts() {
		openAccount(SOURCE, OPENING_BALANCE, EUR);
		openAccount(DESTINATION, 0L, EUR);
	}

	@Test
	@DisplayName("requesting a Transfer opens one outstanding row per required Check")
	void opensOneOutstandingRowPerRequiredCheck() {
		Transfer requested = reservation.reserve(transferOf(80_00L, SOURCE, DESTINATION));

		assertThat(checkLedgerRows())
				.allSatisfy(row -> {
					assertThat(row).containsEntry("TRANSFER_ID", requested.getId());
					assertThat(row).containsEntry("VERDICT", null);
				})
				.extracting(row -> row.get("REQUIRED_CHECK"))
				.containsExactlyInAnyOrder("FRAUD", "MANUAL_APPROVAL");
	}

	/**
	 * The two Transfers get a ledger each rather than sharing one, which is the difference
	 * between a Check Ledger and a lookup of what a Check service has ever said.
	 */
	@Test
	@DisplayName("a second Transfer gets a ledger of its own")
	void opensASeparateLedgerForEachTransfer() {
		Transfer first = reservation.reserve(transferOf(80_00L, SOURCE, DESTINATION));
		Transfer second = reservation.reserve(transferOf(30_00L, SOURCE, DESTINATION));

		assertThat(checkLedgerRows())
				.hasSize(4)
				.extracting(row -> row.get("TRANSFER_ID"))
				.containsOnly(first.getId(), second.getId());
	}

	@Test
	@DisplayName("a refused Transfer leaves no ledger behind either")
	void writesNoLedgerWhenTheTransferIsRefused() {
		assertThatThrownBy(() -> reservation.reserve(transferOf(OPENING_BALANCE + 1, SOURCE, DESTINATION)))
				.isInstanceOf(InsufficientFundsException.class);

		assertThat(transferRows()).isEmpty();
		assertThat(checkLedgerRows()).isEmpty();
	}

	/**
	 * Mandatory propagation asserted directly, rather than inferred from the fact that the
	 * one caller happens to be transactional. It is what stops the next caller — ticket 20's
	 * settlement, a fixture, a script — from opening a ledger that commits separately from
	 * the Transfer it belongs to.
	 */
	@Test
	@DisplayName("a ledger cannot be opened outside a transaction")
	void refusesToOpenALedgerWithNoTransaction() {
		Money euro = new Money(10_00L, EUR);
		Transfer unwritten = new Transfer(SOURCE, DESTINATION, euro, euro, REQUESTED_AT);

		assertThatThrownBy(() -> ledger.openFor(unwritten))
				.isInstanceOf(IllegalTransactionStateException.class);

		assertThat(checkLedgerRows()).isEmpty();
	}

	/**
	 * The check constraint doing its job, and not merely being present. {@code
	 * V4__check_ledger.sql} allows a null Verdict beside an {@code = any (array[…])} list,
	 * and the migration README records that the obvious way to write such a list is broken
	 * on H2 in a way that fails <em>every</em> insert. The tests above would catch that half;
	 * this catches the other, where the constraint accepts everything instead.
	 *
	 * <p>An update rather than an insert, and not incidentally: an insert of a bad Verdict
	 * for a Check the Transfer already has is refused by the uniqueness rule below before the
	 * constraint under test is ever consulted, which is a green test proving the wrong thing.
	 * It is also the shape ticket 20 will write a Verdict in.
	 */
	@Test
	@DisplayName("the table refuses a Verdict that is not one of the two")
	void refusesAVerdictTheDomainDoesNotHave() {
		reservation.reserve(transferOf(80_00L, SOURCE, DESTINATION));

		assertThatThrownBy(() -> database.update("UPDATE check_ledger SET verdict = 'MAYBE'"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/**
	 * One row per Check per Transfer, enforced where a Check service's retry will actually
	 * hit it. Ticket 20 makes at-least-once delivery safe by guarding the update on the row
	 * still being unanswered; that guard is only worth anything if a second delivery cannot
	 * simply write a second row instead.
	 */
	@Test
	@DisplayName("a Transfer cannot collect a second row for the same Check")
	void refusesASecondRowForACheckTheTransferAlreadyHas() {
		Transfer requested = reservation.reserve(transferOf(80_00L, SOURCE, DESTINATION));

		assertThatThrownBy(() -> database.update("""
				INSERT INTO check_ledger (transfer_id, required_check, verdict)
				VALUES (?, 'FRAUD', 'APPROVED')
				""", requested.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
