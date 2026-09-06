package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.accounts.Account;
import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A {@link Transfer} survives the trip to the database and back in each of its four
 * statuses, keeping its two amounts in the two currencies they are denominated in.
 *
 * <p>The status is the reason for the round trip: stored by name it is readable in the
 * table and stable against a reordering of the enum, and left unannotated JPA would store
 * an ordinal instead — silently, and correctly enough to pass a test that only ever wrote
 * and read through the same mapping. So each case asserts the string that reached the
 * column as well as the value that came back.
 *
 * <p>Startup is half of every assertion below: {@code validate} has already compared the
 * entity against {@code V2__transfers.sql} by the time any method runs.
 */
@DataJpaTest
@TestPropertySource(properties =
		// The application's own setting, restated because the claim above rests on it.
		"spring.jpa.hibernate.ddl-auto=validate")
class TransferRoundTripsInEveryStatusTest {

	private static final Instant REQUESTED_AT = Instant.parse("2026-09-05T10:15:30Z");

	/** Six decimal places, which is the scale this application's stand-in provider quotes at. */
	private static final BigDecimal QUOTED_RATE = new BigDecimal("390.000000");

	private static final Instant RATE_FETCHED_AT = Instant.parse("2026-09-05T10:15:29Z");

	@Autowired
	private TestEntityManager entityManager;

	/**
	 * Writing a Transfer is {@link TransferRepository}'s and reading one back is
	 * {@link TransferQueries}', so a round trip needs both halves and the fields are named
	 * for which half they are.
	 */
	@Autowired
	private TransferRepository writtenTransfers;

	@Autowired
	private TransferQueries storedTransfers;

	private Long sourceAccountId;
	private Long destinationAccountId;

	@BeforeEach
	void openTwoAccounts() {
		sourceAccountId = openAccount(new Money(500_00L, Currency.EUR));
		destinationAccountId = openAccount(new Money(0L, Currency.HUF));
	}

	@Test
	@DisplayName("a requested Transfer is PENDING from the moment it was requested")
	void isPendingFromTheMomentItWasRequested() {
		Long id = writtenTransfers.save(pendingTransfer()).getId();
		entityManager.flush();
		entityManager.clear();

		Transfer reopened = storedTransfers.findById(id).orElseThrow();

		assertThat(reopened.getStatus()).isEqualTo(TransferStatus.PENDING);
		assertThat(reopened.getSourceAccountId()).isEqualTo(sourceAccountId);
		assertThat(reopened.getDestinationAccountId()).isEqualTo(destinationAccountId);
		assertThat(reopened.getCreatedAt()).isEqualTo(REQUESTED_AT);
	}

	/**
	 * The transitions themselves belong to tickets 13, 20 and 23, so the status is moved
	 * here by a bulk update — which still binds the enum through the entity's own mapping,
	 * and so exercises the direction a native {@code INSERT} of the string would skip.
	 */
	@ParameterizedTest
	@EnumSource(TransferStatus.class)
	@DisplayName("a Transfer round-trips in each of its statuses, stored by name")
	void roundTripsInEachStatus(TransferStatus status) {
		Long id = writtenTransfers.save(pendingTransfer()).getId();

		advanceTo(id, status);
		entityManager.clear();

		Transfer reopened = storedTransfers.findById(id).orElseThrow();
		assertThat(reopened.getStatus()).isEqualTo(status);
		assertThat(entityManager.getEntityManager()
				.createNativeQuery("SELECT status FROM transfers")
				.getSingleResult()).hasToString(status.name());
	}

	/**
	 * Read in SQL rather than through Hibernate, so this asserts what is in the database
	 * rather than what the mapping is willing to say about it — a round trip through the
	 * same mapping would agree with itself whichever columns it chose.
	 */
	@Test
	@DisplayName("the debited and the credited amount each get their own pair of columns")
	void keepsTheTwoAmountsInColumnsOfTheirOwn() {
		writtenTransfers.save(pendingTransfer());
		entityManager.flush();
		entityManager.clear();

		@SuppressWarnings("unchecked")
		List<Object[]> rows = entityManager.getEntityManager().createNativeQuery("""
				SELECT debited_amount_minor_units, debited_amount_currency,
				       credited_amount_minor_units, credited_amount_currency
				FROM transfers
				""").getResultList();

		assertThat(rows).hasSize(1);
		assertThat(((Number) rows.getFirst()[0]).longValue()).isEqualTo(120_00L);
		assertThat(rows.getFirst()[1]).hasToString("EUR");
		assertThat(((Number) rows.getFirst()[2]).longValue()).isEqualTo(46_800L);
		assertThat(rows.getFirst()[3]).hasToString("HUF");
	}

	@Test
	@DisplayName("the table refuses a status the domain has no name for")
	void refusesAStatusTheDomainHasNoNameFor() {
		assertThatThrownBy(() -> insertTransfer(sourceAccountId, destinationAccountId, 120_00L, "VOIDED"))
				.hasStackTraceContaining("TRANSFERS_STATUS_IS_KNOWN");
	}

	/** The backstop under ticket 14's self-transfer check, not a replacement for it. */
	@Test
	@DisplayName("the table refuses a Transfer from an Account to itself")
	void refusesATransferFromAnAccountToItself() {
		assertThatThrownBy(() -> insertTransfer(sourceAccountId, sourceAccountId, 120_00L, "PENDING"))
				.hasStackTraceContaining("TRANSFERS_TWO_DISTINCT_ACCOUNTS");
	}

	/** No Transfer may debit the source and credit nothing — design decision 16, via ticket 07. */
	@Test
	@DisplayName("the table refuses a Transfer that would move nothing")
	void refusesATransferThatWouldMoveNothing() {
		assertThatThrownBy(() -> insertTransfer(sourceAccountId, destinationAccountId, 0L, "PENDING"))
				.hasStackTraceContaining("TRANSFERS_POSITIVE_AMOUNTS");
	}

	@Test
	@DisplayName("the table refuses a Transfer against an Account that does not exist")
	void refusesATransferAgainstAnAccountThatDoesNotExist() {
		assertThatThrownBy(() -> insertTransfer(sourceAccountId, 4_242L, 120_00L, "PENDING"))
				.hasStackTraceContaining("TRANSFERS_DESTINATION_ACCOUNT");
	}

	/**
	 * The Exchange Rate reaches columns of its own and comes back the same number, read in
	 * SQL for the reason the two amounts are: a round trip through the mapping would agree
	 * with itself whatever scale it chose.
	 *
	 * <p>Compared by value rather than by equality, because the column's scale is ten and
	 * the provider quotes six — {@code 390.000000} and {@code 390.0000000000} are the same
	 * rate and are not the same {@link BigDecimal}.
	 */
	@Test
	@DisplayName("the Exchange Rate and the moment it was fetched round-trip with the Transfer")
	void keepsTheExchangeRateAndTheMomentItWasFetched() {
		Long id = writtenTransfers.save(pendingTransfer()).getId();
		entityManager.flush();
		entityManager.clear();

		Transfer reopened = storedTransfers.findById(id).orElseThrow();

		assertThat(reopened.getExchangeRate()).isEqualByComparingTo(QUOTED_RATE);
		assertThat(reopened.getExchangeRateFetchedAt()).isEqualTo(RATE_FETCHED_AT);

		Object[] stored = (Object[]) entityManager.getEntityManager()
				.createNativeQuery("SELECT exchange_rate, exchange_rate_fetched_at FROM transfers")
				.getSingleResult();
		assertThat((BigDecimal) stored[0]).isEqualByComparingTo(QUOTED_RATE);
	}

	/**
	 * The three ways the rate, its timestamp and the two Currencies can disagree, each
	 * refused by the constraint rather than only by the entity that writes them. Written as
	 * refusals rather than as one accepted good row, on the migration README's rule: a
	 * {@code check} that has quietly stopped evaluating still lets every good row in.
	 */
	@Test
	@DisplayName("the table refuses a cross-Currency Transfer that names no Exchange Rate")
	void refusesACrossCurrencyTransferWithNoRate() {
		assertThatThrownBy(() -> insertTransferQuotedAt(
				sourceAccountId, destinationAccountId, 120_00L, "PENDING", "HUF", null, null))
				.hasStackTraceContaining("TRANSFERS_RATE_IFF_CROSS_CURRENCY");
	}

	@Test
	@DisplayName("the table refuses a same-Currency Transfer carrying an Exchange Rate")
	void refusesASameCurrencyTransferCarryingARate() {
		assertThatThrownBy(() -> insertTransferQuotedAt(
				sourceAccountId, destinationAccountId, 120_00L, "PENDING", "EUR", QUOTED_RATE, RATE_FETCHED_AT))
				.hasStackTraceContaining("TRANSFERS_RATE_IFF_CROSS_CURRENCY");
	}

	@Test
	@DisplayName("the table refuses an Exchange Rate that does not say when it was fetched")
	void refusesARateWithNoFetchTimestamp() {
		assertThatThrownBy(() -> insertTransferQuotedAt(
				sourceAccountId, destinationAccountId, 120_00L, "PENDING", "HUF", QUOTED_RATE, null))
				.hasStackTraceContaining("TRANSFERS_RATE_IFF_CROSS_CURRENCY");
	}

	/**
	 * Denominated differently on its two sides, so it carries an Exchange Rate — and so the
	 * round trip covers the four columns ticket 26 added as well as the ones before them.
	 */
	private Transfer pendingTransfer() {
		return new Transfer(sourceAccountId, destinationAccountId,
				new ConvertedAmounts(new Money(120_00L, Currency.EUR), new Money(46_800L, Currency.HUF),
						QUOTED_RATE, RATE_FETCHED_AT),
				REQUESTED_AT);
	}

	private Long openAccount(Money openingBalance) {
		return entityManager.persistAndGetId(new Account(openingBalance), Long.class);
	}

	private void advanceTo(Long id, TransferStatus status) {
		entityManager.getEntityManager()
				.createQuery("UPDATE Transfer t SET t.status = :status WHERE t.id = :id")
				.setParameter("status", status)
				.setParameter("id", id)
				.executeUpdate();
	}

	/**
	 * A row that breaks exactly one rule at a time. The two Currencies differ, so it carries
	 * an Exchange Rate — without one the rate constraint would fire alongside whichever rule
	 * a test meant to break, and each assertion below names the constraint it expects.
	 */
	private void insertTransfer(Long sourceId, Long destinationId, long minorUnits, String status) {
		insertTransferQuotedAt(sourceId, destinationId, minorUnits, status, "HUF", QUOTED_RATE, RATE_FETCHED_AT);
	}

	private void insertTransferQuotedAt(Long sourceId, Long destinationId, long minorUnits, String status,
			String creditedCurrency, @Nullable BigDecimal exchangeRate, @Nullable Instant fetchedAt) {
		entityManager.getEntityManager().createNativeQuery("""
						INSERT INTO transfers (source_account_id, destination_account_id,
						                       debited_amount_minor_units, debited_amount_currency,
						                       credited_amount_minor_units, credited_amount_currency,
						                       exchange_rate, exchange_rate_fetched_at,
						                       status, created_at)
						VALUES (?, ?, ?, 'EUR', ?, ?, ?, ?, ?, ?)
						""")
				.setParameter(1, sourceId)
				.setParameter(2, destinationId)
				.setParameter(3, minorUnits)
				.setParameter(4, minorUnits)
				.setParameter(5, creditedCurrency)
				.setParameter(6, exchangeRate)
				.setParameter(7, fetchedAt)
				.setParameter(8, status)
				.setParameter(9, REQUESTED_AT)
				.executeUpdate();
	}
}
