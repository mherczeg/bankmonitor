package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three movements that end a reservation, and the one figure each of them may not move.
 *
 * <p>{@code AccountReservesAgainstItsAvailableBalanceTest} covers the way in; these are the
 * three ways out. The distinction they exist to make impossible to get wrong is that
 * settling lowers <em>both</em> figures while releasing lowers only the Reserved Amount — an
 * Account settled by a debit alone would keep holding the settled Transfer's reservation for
 * ever, and every balance read afterwards would be short by it.
 *
 * <p>No database and no Spring, for {@code AccountReservesAgainstItsAvailableBalanceTest}'s
 * reason: what is decidable on one object is the arithmetic and the refusal. That the
 * movements reach the table under a row lock is
 * {@code RecordedVerdictsAdvanceTheTransferTest}'s.
 */
class AccountSettlesAndReleasesWhatItReservedTest {

	private static final Money OPENING_BALANCE = new Money(250_00L, Currency.EUR);

	private static final Money RESERVED = new Money(80_00L, Currency.EUR);

	private final Account account = new Account(OPENING_BALANCE);

	private final Account destination = new Account(Money.zero(Currency.EUR));

	/**
	 * The bug the ticket's wording exists to prevent, asserted as the Reserved Amount rather
	 * than as the balance: an implementation that debits and forgets to consume the
	 * reservation passes every balance assertion and leaves the Account permanently unable to
	 * spend what it still holds.
	 */
	@Test
	@DisplayName("settling lowers the balance and the Reserved Amount by the same amount")
	void settlingLowersBothFigures() {
		account.reserve(RESERVED);

		account.settle(RESERVED);

		assertThat(account.getBalance()).isEqualTo(new Money(170_00L, Currency.EUR));
		assertThat(account.getReservedAmount()).isEqualTo(Money.zero(Currency.EUR));
		assertThat(account.getAvailableBalance()).isEqualTo(new Money(170_00L, Currency.EUR));
	}

	/**
	 * Settlement takes the money the Available Balance had already given up, so the figure an
	 * overdraft check tests against does not move. Stated separately because it is what makes
	 * a settlement invisible to every other in-flight Transfer.
	 */
	@Test
	@DisplayName("settling leaves the Available Balance where the reservation had already put it")
	void settlingLeavesTheAvailableBalanceUnmoved() {
		account.reserve(RESERVED);
		Money availableWhileReserved = account.getAvailableBalance();

		account.settle(RESERVED);

		assertThat(account.getAvailableBalance()).isEqualTo(availableWhileReserved);
	}

	@Test
	@DisplayName("releasing gives the Available Balance back and moves no money")
	void releasingGivesTheAvailableBalanceBack() {
		account.reserve(RESERVED);

		account.release(RESERVED);

		assertThat(account.getBalance()).isEqualTo(OPENING_BALANCE);
		assertThat(account.getReservedAmount()).isEqualTo(Money.zero(Currency.EUR));
		assertThat(account.getAvailableBalance()).isEqualTo(OPENING_BALANCE);
	}

	/**
	 * Nothing is reserved on the receiving side of a Transfer — the money arrives owing
	 * nobody anything — so crediting raises the balance and the Available Balance together.
	 */
	@Test
	@DisplayName("crediting raises the balance and reserves nothing against it")
	void creditingRaisesTheBalanceAndReservesNothing() {
		destination.credit(RESERVED);

		assertThat(destination.getBalance()).isEqualTo(RESERVED);
		assertThat(destination.getReservedAmount()).isEqualTo(Money.zero(Currency.EUR));
		assertThat(destination.getAvailableBalance()).isEqualTo(RESERVED);
	}

	/** Other reservations survive one Transfer settling: each holds its own part of the balance. */
	@Test
	@DisplayName("settling one reservation leaves another one standing")
	void settlingOneReservationLeavesAnotherStanding() {
		account.reserve(RESERVED);
		account.reserve(new Money(30_00L, Currency.EUR));

		account.settle(RESERVED);

		assertThat(account.getReservedAmount()).isEqualTo(new Money(30_00L, Currency.EUR));
		assertThat(account.getBalance()).isEqualTo(new Money(170_00L, Currency.EUR));
	}

	/**
	 * A settlement larger than what is reserved is a Transfer settling twice, or settling
	 * against a reservation somebody else already consumed. Refused on the entity for the
	 * same reason the overdraft check lives there: an Account that could be talked into a
	 * negative Reserved Amount would go on to report an Available Balance larger than it
	 * holds, and the next reservation would overdraw against the excess.
	 */
	@Test
	@DisplayName("settling more than is reserved is refused, and moves neither figure")
	void refusesToSettleMoreThanIsReserved() {
		account.reserve(RESERVED);

		assertThatThrownBy(() -> account.settle(new Money(80_01L, Currency.EUR)))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(account.getBalance()).isEqualTo(OPENING_BALANCE);
		assertThat(account.getReservedAmount()).isEqualTo(RESERVED);
	}

	@Test
	@DisplayName("releasing more than is reserved is refused, and moves neither figure")
	void refusesToReleaseMoreThanIsReserved() {
		account.reserve(RESERVED);

		assertThatThrownBy(() -> account.release(new Money(80_01L, Currency.EUR)))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(account.getBalance()).isEqualTo(OPENING_BALANCE);
		assertThat(account.getReservedAmount()).isEqualTo(RESERVED);
	}

	/** Nothing is reserved on an Account nobody has transferred out of, so both are refused. */
	@Test
	@DisplayName("an Account with nothing reserved can neither settle nor release")
	void refusesToSettleOrReleaseAgainstNoReservation() {
		assertThatThrownBy(() -> account.settle(RESERVED)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> account.release(RESERVED)).isInstanceOf(IllegalArgumentException.class);

		assertThat(account.getBalance()).isEqualTo(OPENING_BALANCE);
	}

	/** {@link Money}'s refusal, for {@code reserve}'s reason: a caller that skipped a conversion. */
	@Test
	@DisplayName("a movement in another Currency is refused as a caller's mistake")
	void refusesAMovementInAnotherCurrency() {
		Money dollars = new Money(10_00L, Currency.USD);

		assertThatThrownBy(() -> account.settle(dollars)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> account.release(dollars)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> account.credit(dollars)).isInstanceOf(IllegalArgumentException.class);
	}
}
