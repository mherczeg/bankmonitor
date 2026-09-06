package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.accounts.Account;
import hu.bankmonitor.payments.accounts.AccountLocking;
import hu.bankmonitor.payments.accounts.LockedAccounts;
import hu.bankmonitor.payments.common.Money;
import hu.bankmonitor.payments.transfers.checks.CheckLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static hu.bankmonitor.payments.common.Currency.EUR;
import static hu.bankmonitor.payments.common.Currency.HUF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * How far a Transfer this service will not carry out gets before it is refused — which is
 * the half of a refusal that its status code cannot show.
 *
 * <p>Two refusals, and each stops at a different point on purpose. A self-Transfer is
 * decidable from the request alone, so it is refused before a lock is taken: the alternative
 * is a transaction that locks one row twice under two names before discovering it had
 * nothing to do. A cross-Currency Transfer cannot be decided without the Accounts, so it is
 * refused after the locks and before the write — the earliest point at which the two
 * Currencies are known, and the latest at which nothing has happened yet.
 *
 * <p>{@link AccountLocking} is a mock rather than a database because both claims are about
 * calls that must <em>not</em> happen, and a missing {@code select … for update} is not
 * something a statement log can tell apart from a test that never ran.
 * {@link ReservedFundsReachTheTableTest} is the half that needs real rows.
 */
@ExtendWith(MockitoExtension.class)
class RefusedTransfersWriteNothingTest {

	private static final long SOURCE = 5L;

	private static final long DESTINATION = 9L;

	private static final Instant REQUESTED_AT = Instant.parse("2026-09-06T09:41:00Z");

	@Mock
	private AccountLocking accounts;

	@Mock
	private TransferRepository transfers;

	@Mock
	private CheckLedger ledger;

	private FundsReservation reservation;

	@BeforeEach
	void buildTheReservationOverItsMockedCollaborators() {
		reservation = new FundsReservation(accounts, transfers, ledger);
	}

	@Test
	@DisplayName("a Transfer from an Account to itself is refused before any lock is taken")
	void refusesASelfTransferBeforeTakingALock() {
		assertThatThrownBy(() -> reservation.reserve(new ReservationRequest(SOURCE, SOURCE, 100_50L, REQUESTED_AT)))
				.isInstanceOf(SelfTransferNotAllowedException.class)
				.extracting(thrown -> ((SelfTransferNotAllowedException) thrown).getAccountId())
				.isEqualTo(SOURCE);

		then(accounts).shouldHaveNoInteractions();
		then(transfers).shouldHaveNoInteractions();
		then(ledger).shouldHaveNoInteractions();
	}

	/**
	 * Nothing is reserved either, which is the assertion that fails if the refusal moves one
	 * line later: {@code Account.reserve} raises the Reserved Amount in memory and the
	 * Accounts are attached to the caller's transaction, so a refusal thrown after it has
	 * already written the row it meant to prevent.
	 */
	@Test
	@DisplayName("a cross-Currency Transfer is refused after the locks and before anything is written")
	void refusesACrossCurrencyTransferBeforeWritingAnything() {
		Account source = new Account(new Money(250_00L, EUR));
		Account destination = new Account(new Money(0L, HUF));
		given(accounts.lockForTransfer(SOURCE, DESTINATION)).willReturn(new LockedAccounts(source, destination));

		assertThatThrownBy(() -> reservation.reserve(
				new ReservationRequest(SOURCE, DESTINATION, 100_50L, REQUESTED_AT)))
				.isInstanceOf(CrossCurrencyTransferNotSupportedException.class)
				.satisfies(thrown -> {
					CrossCurrencyTransferNotSupportedException refusal =
							(CrossCurrencyTransferNotSupportedException) thrown;
					assertThat(refusal.getSourceCurrency()).isEqualTo(EUR);
					assertThat(refusal.getDestinationCurrency()).isEqualTo(HUF);
				});

		then(transfers).shouldHaveNoInteractions();
		then(ledger).shouldHaveNoInteractions();
		assertThat(source.getReservedAmount()).isEqualTo(Money.zero(EUR));
	}
}
