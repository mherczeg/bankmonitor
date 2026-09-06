package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.accounts.InsufficientFundsException;
import hu.bankmonitor.payments.accounts.UnknownAccountException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static hu.bankmonitor.payments.common.Currency.EUR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

/**
 * What one reservation leaves behind: a raised Reserved Amount, a {@code PENDING} Transfer,
 * and no money anywhere else.
 *
 * <p>These are the single-threaded halves of the claim.
 * {@link ConcurrentReservationsHoldTheBalanceTest} is where two threads make the ordering of
 * the lock and the balance check matter; here what is being asserted is that the operation
 * writes what it says it writes, and — the more easily lost half — that a refused one writes
 * nothing at all. A reservation and a Transfer that could commit separately would leave funds
 * held against a request that does not exist, so every refusal below reads the tables back to
 * show that neither happened.
 */
class ReservedFundsReachTheTableTest extends ReservationScenario {

	private static final long SOURCE = 1L;
	private static final long DESTINATION = 2L;
	private static final long OPENING_BALANCE = 250_00L;

	@BeforeEach
	void openBothAccounts() {
		openAccount(SOURCE, OPENING_BALANCE, EUR);
		openAccount(DESTINATION, 0L, EUR);
	}

	@Test
	@DisplayName("reserving raises the source Account's Reserved Amount and moves no money")
	void raisesTheReservedAmountAndMovesNoMoney() {
		Transfer requested = reservation.reserve(transferOf(80_00L, SOURCE, DESTINATION));

		assertThat(requested.getId()).isNotNull();
		assertThat(requested.getStatus()).isEqualTo(TransferStatus.PENDING);

		assertThat(balanceOf(SOURCE)).isEqualTo(OPENING_BALANCE);
		assertThat(reservedAmountOf(SOURCE)).isEqualTo(80_00L);
		assertThat(availableBalanceOf(SOURCE)).isEqualTo(170_00L);

		assertThat(balanceOf(DESTINATION)).isZero();
		assertThat(reservedAmountOf(DESTINATION)).isZero();
	}

	/**
	 * A same-Currency Transfer carries the same figure twice, denominated by the source
	 * Account rather than by anything the caller said: {@link ReservationRequest} has only a
	 * count of Minor Units to offer.
	 */
	@Test
	@DisplayName("the PENDING Transfer records both amounts in the Accounts' Currency")
	void recordsThePendingTransferInTheAccountsCurrency() {
		reservation.reserve(transferOf(80_00L, SOURCE, DESTINATION));

		assertThat(transferRows()).singleElement().satisfies(row -> assertThat(row)
				.containsEntry("STATUS", "PENDING")
				.containsEntry("SOURCE_ACCOUNT_ID", SOURCE)
				.containsEntry("DESTINATION_ACCOUNT_ID", DESTINATION)
				.containsEntry("DEBITED_AMOUNT_MINOR_UNITS", 80_00L)
				.containsEntry("DEBITED_AMOUNT_CURRENCY", "EUR")
				.containsEntry("CREDITED_AMOUNT_MINOR_UNITS", 80_00L)
				.containsEntry("CREDITED_AMOUNT_CURRENCY", "EUR"));
	}

	/**
	 * Two Transfers in two transactions, where the second is measured against what the first
	 * committed. That reservations accumulate at all is
	 * {@code AccountReservesAgainstItsAvailableBalanceTest}'s claim about one object in
	 * memory; what this adds is that the raised figure survives the commit.
	 */
	@Test
	@DisplayName("a second reservation stacks on what the first one committed")
	void stacksASecondReservationOnTheCommittedFirst() {
		reservation.reserve(transferOf(80_00L, SOURCE, DESTINATION));
		reservation.reserve(transferOf(30_00L, SOURCE, DESTINATION));

		assertThat(reservedAmountOf(SOURCE)).isEqualTo(110_00L);
		assertThat(transferRows()).hasSize(2);
	}

	@Test
	@DisplayName("a Transfer beyond the Available Balance is refused and nothing is written")
	void refusesATransferBeyondTheAvailableBalanceAndWritesNothing() {
		assertThatThrownBy(() -> reservation.reserve(transferOf(OPENING_BALANCE + 1, SOURCE, DESTINATION)))
				.asInstanceOf(type(InsufficientFundsException.class))
				.satisfies(refused ->
						assertThat(refused.getAvailableBalance().minorUnits()).isEqualTo(OPENING_BALANCE));

		assertThat(reservedAmountOf(SOURCE)).isZero();
		assertThat(transferRows()).isEmpty();
	}

	/**
	 * The Available Balance, not the balance: what an earlier Transfer has spoken for is
	 * spent as far as this one is concerned, even though the source Account still holds it.
	 */
	@Test
	@DisplayName("a Transfer is refused against what an earlier one already reserved")
	void refusesATransferAgainstWhatIsAlreadyReserved() {
		reservation.reserve(transferOf(200_00L, SOURCE, DESTINATION));

		assertThatThrownBy(() -> reservation.reserve(transferOf(50_01L, SOURCE, DESTINATION)))
				.isInstanceOf(InsufficientFundsException.class);

		assertThat(reservedAmountOf(SOURCE)).isEqualTo(200_00L);
		assertThat(transferRows()).hasSize(1);
	}

	@Test
	@DisplayName("a Transfer against an Account that does not exist is refused by ID")
	void refusesATransferAgainstAnAccountThatDoesNotExist() {
		long missing = 404L;

		assertThatThrownBy(() -> reservation.reserve(transferOf(10_00L, SOURCE, missing)))
				.asInstanceOf(type(UnknownAccountException.class))
				.satisfies(refused -> assertThat(refused.getAccountId()).isEqualTo(missing));

		assertThat(reservedAmountOf(SOURCE)).isZero();
		assertThat(transferRows()).isEmpty();
	}
}
