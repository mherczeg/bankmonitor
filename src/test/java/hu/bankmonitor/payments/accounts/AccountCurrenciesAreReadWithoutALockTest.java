package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import hu.bankmonitor.testsupport.CapturingStatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The read design decision 4's phase two needs, and the claim that makes it a legal one:
 * that it takes no lock.
 *
 * <p>An unlocked read of an Account is forbidden everywhere else in this application, so
 * the statement is read out of Hibernate rather than assumed from the absence of an
 * annotation — the mistake this guards against is a future {@code @Lock} added to the query
 * by someone tidying the repository, which would put a row lock in front of the provider
 * call and undo the ordering the whole locking design rests on.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({AccountCurrencies.class, CapturingStatementInspector.class})
class AccountCurrenciesAreReadWithoutALockTest {

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private AccountCurrencies currencies;

	@Autowired
	private CapturingStatementInspector statements;

	@Test
	@DisplayName("both Accounts' Currencies come back named by their role in the Transfer")
	void readsTheCurrencyOfEachSide() {
		long source = openAccount(new Money(400_00L, Currency.EUR));
		long destination = openAccount(new Money(700_000L, Currency.HUF));
		flushAndForget();

		TransferCurrencies pair = currencies.forTransfer(source, destination);

		assertThat(pair.source()).isEqualTo(Currency.EUR);
		assertThat(pair.destination()).isEqualTo(Currency.HUF);
		assertThat(pair.areTheSame()).isFalse();
	}

	@Test
	@DisplayName("two Accounts in one Currency need no conversion")
	void reportsAPairThatNeedsNoRate() {
		long source = openAccount(new Money(400_00L, Currency.EUR));
		long destination = openAccount(new Money(700_00L, Currency.EUR));
		flushAndForget();

		assertThat(currencies.forTransfer(source, destination).areTheSame()).isTrue();
	}

	/**
	 * The claim this class exists for. {@code for update} is what
	 * {@link AccountLockIsASelectForUpdateTest} asserts of the locking read, so its absence
	 * here is the same fact stated the other way round rather than an assertion about
	 * nothing.
	 */
	@Test
	@DisplayName("reading a Currency locks neither row")
	void takesNoLockOnEitherAccount() {
		long source = openAccount(new Money(400_00L, Currency.EUR));
		long destination = openAccount(new Money(700_000L, Currency.HUF));
		flushAndForget();

		currencies.forTransfer(source, destination);

		assertThat(statements.captured())
				.hasSize(2)
				.allSatisfy(sql -> assertThat(sql)
						.contains("from accounts")
						.doesNotContain("for update"));
	}

	@Test
	@DisplayName("an Account with no row has no Currency to quote against, and is named")
	void refusesAnAccountThatDoesNotExist() {
		long source = openAccount(new Money(400_00L, Currency.EUR));
		flushAndForget();

		assertThatThrownBy(() -> currencies.forTransfer(source, 404L))
				.isInstanceOf(UnknownAccountException.class)
				.satisfies(refusal ->
						assertThat(((UnknownAccountException) refusal).getAccountId()).isEqualTo(404L));
	}

	private long openAccount(Money openingBalance) {
		return entityManager.persistAndGetId(new Account(openingBalance), Long.class);
	}

	/** So that the statements captured are this test's reads and not the writes that set it up. */
	private void flushAndForget() {
		entityManager.flush();
		entityManager.clear();
		statements.forget();
	}
}
