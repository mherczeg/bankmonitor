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

import java.math.BigDecimal;
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
 * nothing to do. Amounts that do not match the Accounts they name cannot be decided without
 * the Accounts, so that one is refused after the locks and before the write — the earliest
 * point at which the locked Currencies are known, and the latest at which nothing has
 * happened yet.
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
		assertThatThrownBy(() -> reservation.reserve(
				new ReservationRequest(SOURCE, SOURCE, 100_50L, REQUESTED_AT),
				ConvertedAmounts.unconverted(new Money(100_50L, EUR))))
				.isInstanceOf(SelfTransferNotAllowedException.class)
				.extracting(thrown -> ((SelfTransferNotAllowedException) thrown).getAccountId())
				.isEqualTo(SOURCE);

		then(accounts).shouldHaveNoInteractions();
		then(transfers).shouldHaveNoInteractions();
		then(ledger).shouldHaveNoInteractions();
	}

	/**
	 * The seam between design decision 4's two phases: the amounts were resolved against
	 * Currencies read without a lock, and this is the point at which the locked rows get to
	 * disagree. Nothing can make them disagree today — a Currency is fixed when an Account is
	 * opened — so the only way to reach the guard is to hand the reservation amounts that
	 * were quoted for other Accounts, which is what this does.
	 *
	 * <p>Nothing is reserved either, which is the assertion that fails if the check moves one
	 * line later: {@code Account.reserve} raises the Reserved Amount in memory and the
	 * Accounts are attached to the caller's transaction, so a refusal thrown after it has
	 * already written the row it meant to prevent.
	 */
	@Test
	@DisplayName("amounts denominated in something the locked Accounts are not write nothing")
	void refusesAmountsTheLockedAccountsDoNotAgreeWithBeforeWritingAnything() {
		Account source = new Account(new Money(250_00L, EUR));
		Account destination = new Account(new Money(0L, EUR));
		given(accounts.lockForTransfer(SOURCE, DESTINATION)).willReturn(new LockedAccounts(source, destination));

		assertThatThrownBy(() -> reservation.reserve(
				new ReservationRequest(SOURCE, DESTINATION, 100_50L, REQUESTED_AT),
				new ConvertedAmounts(new Money(100_50L, EUR), new Money(39_697L, HUF),
						new BigDecimal("395.00"), REQUESTED_AT)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("EUR")
				.hasMessageContaining("HUF");

		then(transfers).shouldHaveNoInteractions();
		then(ledger).shouldHaveNoInteractions();
		assertThat(source.getReservedAmount()).isEqualTo(Money.zero(EUR));
	}
}
