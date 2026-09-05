package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

/**
 * Reserving against an {@link Account}: what it moves, what it refuses, and the figure it
 * tests against.
 *
 * <p>No database and no Spring, because none of the claims here are about either. That the
 * reservation reaches the table under a row lock is {@code ReservedFundsReachTheTableTest}'s,
 * and that two of them cannot both spend the same Available Balance is
 * {@code ConcurrentReservationsHoldTheBalanceTest}'s. What is decidable on one object is the
 * arithmetic and the refusal — and the refusal is why the check lives on the entity rather
 * than in the service that calls it: an Account that could be talked into reserving more than
 * it has would put the invariant in whichever caller remembered it.
 */
class AccountReservesAgainstItsAvailableBalanceTest {

	private static final Money OPENING_BALANCE = new Money(250_00L, Currency.EUR);

	private final Account account = new Account(OPENING_BALANCE);

	@Test
	@DisplayName("reserving raises the Reserved Amount and moves no money")
	void raisesTheReservedAmountAndMovesNoMoney() {
		account.reserve(new Money(80_00L, Currency.EUR));

		assertThat(account.getBalance()).isEqualTo(OPENING_BALANCE);
		assertThat(account.getReservedAmount()).isEqualTo(new Money(80_00L, Currency.EUR));
		assertThat(account.getAvailableBalance()).isEqualTo(new Money(170_00L, Currency.EUR));
	}

	/** Reservations accumulate: each in-flight Transfer holds its own part of the balance. */
	@Test
	@DisplayName("a second reservation is held alongside the first")
	void holdsASecondReservationAlongsideTheFirst() {
		account.reserve(new Money(80_00L, Currency.EUR));
		account.reserve(new Money(30_00L, Currency.EUR));

		assertThat(account.getReservedAmount()).isEqualTo(new Money(110_00L, Currency.EUR));
		assertThat(account.getAvailableBalance()).isEqualTo(new Money(140_00L, Currency.EUR));
	}

	/** An Available Balance equal to the amount covers it; the refusal starts one Minor Unit later. */
	@Test
	@DisplayName("the whole Available Balance can be reserved, down to the last Minor Unit")
	void reservesTheWholeAvailableBalance() {
		account.reserve(OPENING_BALANCE);

		assertThat(account.getAvailableBalance()).isEqualTo(Money.zero(Currency.EUR));
	}

	@Test
	@DisplayName("a reservation larger than the Available Balance is refused")
	void refusesToReserveMoreThanIsAvailable() {
		Money beyondReach = new Money(250_01L, Currency.EUR);

		assertThatThrownBy(() -> account.reserve(beyondReach))
				.asInstanceOf(type(InsufficientFundsException.class))
				.satisfies(refused -> {
					assertThat(refused.getAvailableBalance()).isEqualTo(OPENING_BALANCE);
					assertThat(refused.getRequestedAmount()).isEqualTo(beyondReach);
				});

		assertThat(account.getReservedAmount()).isEqualTo(Money.zero(Currency.EUR));
	}

	/**
	 * What is already reserved is spent as far as a later reservation is concerned, which is
	 * the whole reason the check tests the Available Balance rather than the balance.
	 */
	@Test
	@DisplayName("a reservation is refused against what earlier ones have already spoken for")
	void refusesToReserveWhatIsAlreadySpokenFor() {
		account.reserve(new Money(200_00L, Currency.EUR));

		assertThatThrownBy(() -> account.reserve(new Money(50_01L, Currency.EUR)))
				.isInstanceOf(InsufficientFundsException.class);

		assertThat(account.getReservedAmount()).isEqualTo(new Money(200_00L, Currency.EUR));
	}

	/**
	 * {@link Money} refuses the comparison rather than the Account refusing the reservation:
	 * an amount in another Currency is a caller that skipped a conversion, not a request an
	 * Account has an answer to.
	 */
	@Test
	@DisplayName("a reservation in another Currency is refused as a caller's mistake")
	void refusesAReservationInAnotherCurrency() {
		assertThatThrownBy(() -> account.reserve(new Money(10_00L, Currency.USD)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("EUR")
				.hasMessageContaining("USD");
	}
}
