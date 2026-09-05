package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An {@link Account}'s two Money figures reach four columns of their own, and the table
 * refuses the rows the entity has no way to produce.
 *
 * <p>Two embeddables of the same type is the case the implicit naming strategy cannot
 * serve: it names an embeddable's columns after the component's own fields and not after
 * the field holding it, so both {@code Money} figures would ask for {@code minor_units}
 * and {@code currency}. The {@code @AttributeOverride}s on the entity are what keep them
 * apart, and the column names asserted here are the ones {@code V1__accounts.sql} was
 * written to match. Design decision 29 has the mapping story.
 *
 * <p>Startup is half of every assertion below: {@code validate} has already compared the
 * entity against the migration by the time any method runs.
 */
@DataJpaTest
@TestPropertySource(properties =
		// The application's own setting, restated because the claim above rests on it.
		"spring.jpa.hibernate.ddl-auto=validate")
class AccountHoldsTwoFiguresInOneCurrencyTest {

	@Autowired
	private TestEntityManager entityManager;

	@Test
	@DisplayName("a new Account holds its opening balance with nothing reserved against it")
	void opensWithNothingReservedAgainstIt() {
		Money opening = new Money(250_00L, Currency.EUR);

		Long id = entityManager.persistAndGetId(new Account(opening), Long.class);
		entityManager.flush();
		entityManager.clear();

		Account reopened = entityManager.find(Account.class, id);

		assertThat(reopened.getBalance()).isEqualTo(opening);
		assertThat(reopened.getReservedAmount()).isEqualTo(Money.zero(Currency.EUR));
		assertThat(reopened.getAvailableBalance()).isEqualTo(opening);
		assertThat(reopened.getCurrency()).isEqualTo(Currency.EUR);
	}

	/**
	 * Read in SQL rather than through Hibernate, so this asserts what is in the database
	 * rather than what the mapping is willing to say about it — a round trip through the
	 * same mapping would agree with itself whichever columns it chose.
	 */
	@Test
	@DisplayName("the balance and the Reserved Amount each get their own pair of columns")
	void keepsTheTwoFiguresInColumnsOfTheirOwn() {
		entityManager.persistAndFlush(new Account(new Money(900_00L, Currency.USD)));
		entityManager.clear();

		@SuppressWarnings("unchecked")
		List<Object[]> rows = entityManager.getEntityManager().createNativeQuery("""
				SELECT balance_minor_units, balance_currency,
				       reserved_amount_minor_units, reserved_amount_currency
				FROM accounts
				""").getResultList();

		assertThat(rows).hasSize(1);
		assertThat(((Number) rows.getFirst()[0]).longValue()).isEqualTo(900_00L);
		assertThat(rows.getFirst()[1]).hasToString("USD");
		assertThat(((Number) rows.getFirst()[2]).longValue()).isZero();
		assertThat(rows.getFirst()[3]).hasToString("USD");
	}

	/**
	 * The other direction, and the only one that can reach a non-zero Reserved Amount
	 * today: raising it is ticket 13's reservation, so until that lands the row is written
	 * in SQL. HUF because its Minor Units are not hundredths, so a figure that survived a
	 * stray division by a hundred would not read back equal.
	 */
	@Test
	@DisplayName("money reserved against an Account comes back out of its Available Balance")
	void subtractsAReservationFromTheAvailableBalance() {
		insertAccount(90_000L, "HUF", 25_000L, "HUF");
		entityManager.clear();

		Account account = entityManager.find(Account.class, onlyAccountId());

		assertThat(account.getBalance()).isEqualTo(new Money(90_000L, Currency.HUF));
		assertThat(account.getReservedAmount()).isEqualTo(new Money(25_000L, Currency.HUF));
		assertThat(account.getAvailableBalance()).isEqualTo(new Money(65_000L, Currency.HUF));
	}

	/** The backstop under ticket 13's overdraft check, not a replacement for it. */
	@Test
	@DisplayName("the table refuses a Reserved Amount larger than the balance it is held against")
	void refusesToReserveMoreThanTheAccountHolds() {
		assertThatThrownBy(() -> insertAccount(100L, "EUR", 101L, "EUR"))
				.hasStackTraceContaining("ACCOUNTS_RESERVED_WITHIN_BALANCE");
	}

	@Test
	@DisplayName("the table refuses an Account whose two figures are in different currencies")
	void refusesAnAccountDenominatedInTwoCurrencies() {
		assertThatThrownBy(() -> insertAccount(100L, "EUR", 0L, "USD"))
				.hasStackTraceContaining("ACCOUNTS_ONE_CURRENCY");
	}

	private void insertAccount(long balanceMinorUnits, String balanceCurrency,
			long reservedMinorUnits, String reservedCurrency) {
		entityManager.getEntityManager().createNativeQuery("""
						INSERT INTO accounts (balance_minor_units, balance_currency,
						                      reserved_amount_minor_units, reserved_amount_currency)
						VALUES (?, ?, ?, ?)
						""")
				.setParameter(1, balanceMinorUnits)
				.setParameter(2, balanceCurrency)
				.setParameter(3, reservedMinorUnits)
				.setParameter(4, reservedCurrency)
				.executeUpdate();
	}

	/** The identity column picks the value, so the row has to say which one it got. */
	private Long onlyAccountId() {
		return ((Number) entityManager.getEntityManager()
				.createNativeQuery("SELECT id FROM accounts")
				.getSingleResult()).longValue();
	}
}
