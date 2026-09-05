package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import hu.bankmonitor.testsupport.CapturingStatementInspector;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The half of design decision 6 that only a database can answer: that
 * {@link AccountLocking} really does take row locks, rather than reading two rows under an
 * annotation nobody has checked.
 *
 * <p>The statements are read out of Hibernate rather than inferred from the mapping, for
 * the same reason {@code AccountHoldsTwoFiguresInOneCurrencyTest} reads its columns in
 * SQL: an assertion phrased through the thing under test would agree with whatever that
 * thing decided to do. What is asserted is the shape the deadlock argument depends on —
 * one {@code select … for update} per Account, so that "which lock was taken first" is a
 * question the wire has an answer to at all.
 *
 * <p>Which of the two comes first is not decidable here, because both statements are the
 * same SQL with a different bound parameter;
 * {@link AccountsLockInAscendingIdOrderTest} is where that half lives.
 */
@DataJpaTest
@TestPropertySource(properties =
		// The application's own setting; the slice would otherwise export the schema itself.
		"spring.jpa.hibernate.ddl-auto=validate")
@Import({AccountLocking.class, CapturingStatementInspector.class})
class AccountLockIsASelectForUpdateTest {

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private AccountLocking locking;

	@Autowired
	private CapturingStatementInspector statements;

	@Test
	@DisplayName("locking a Transfer's Accounts issues one select-for-update per Account")
	void takesARowLockOnEachAccountSeparately() {
		long first = openAccount(new Money(400_00L, Currency.EUR));
		long second = openAccount(new Money(700_00L, Currency.USD));
		entityManager.flush();
		entityManager.clear();
		statements.forget();

		locking.lockForTransfer(second, first);

		assertThat(statements.captured())
				.hasSize(2)
				.allSatisfy(sql -> assertThat(sql)
						.contains("from accounts")
						.contains("where")
						.endsWith("for update"));
	}

	/**
	 * The lock has to outlive the read for the balance check that follows it to mean
	 * anything, so the operation refuses to run where nothing would hold it. Mandatory
	 * propagation is what turns a caller that forgot {@code @Transactional} into a failure
	 * here rather than a race in production.
	 *
	 * <p>{@code NOT_SUPPORTED} is what takes the transaction {@code @DataJpaTest} would
	 * otherwise wrap this method in away again. No Accounts are opened for it because it
	 * must not reach the table at all.
	 */
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("locking outside a transaction is refused rather than quietly useless")
	void refusesToLockWithNoTransactionToHoldTheLock() {
		assertThatThrownBy(() -> locking.lockForTransfer(1L, 2L))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	private long openAccount(Money openingBalance) {
		return entityManager.persistAndGetId(new Account(openingBalance), Long.class);
	}

}
