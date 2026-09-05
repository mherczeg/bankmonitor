package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.accounts.Account;
import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

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

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private TransferRepository transfers;

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
		Long id = transfers.save(pendingTransfer()).getId();
		entityManager.flush();
		entityManager.clear();

		Transfer reopened = transfers.findById(id).orElseThrow();

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
		Long id = transfers.save(pendingTransfer()).getId();

		advanceTo(id, status);
		entityManager.clear();

		Transfer reopened = transfers.findById(id).orElseThrow();
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
		transfers.save(pendingTransfer());
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

	private Transfer pendingTransfer() {
		return new Transfer(sourceAccountId, destinationAccountId,
				new Money(120_00L, Currency.EUR), new Money(46_800L, Currency.HUF), REQUESTED_AT);
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

	private void insertTransfer(Long sourceId, Long destinationId, long minorUnits, String status) {
		entityManager.getEntityManager().createNativeQuery("""
						INSERT INTO transfers (source_account_id, destination_account_id,
						                       debited_amount_minor_units, debited_amount_currency,
						                       credited_amount_minor_units, credited_amount_currency,
						                       status, created_at)
						VALUES (?, ?, ?, 'EUR', ?, 'HUF', ?, ?)
						""")
				.setParameter(1, sourceId)
				.setParameter(2, destinationId)
				.setParameter(3, minorUnits)
				.setParameter(4, minorUnits)
				.setParameter(5, status)
				.setParameter(6, REQUESTED_AT)
				.executeUpdate();
	}
}
