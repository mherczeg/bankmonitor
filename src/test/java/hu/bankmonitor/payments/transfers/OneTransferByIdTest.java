package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import hu.bankmonitor.payments.common.ProblemType;
import hu.bankmonitor.testsupport.BootedApplicationTest;
import hu.bankmonitor.testsupport.TransferRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static hu.bankmonitor.testsupport.TransferRows.DESTINATION_ACCOUNT;
import static hu.bankmonitor.testsupport.TransferRows.SOURCE_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/transfers/{id}}: the resource a Transfer gets of its own, which is what
 * lets a pending Transfer's state live in a URL and survive a refresh (ticket 41).
 *
 * <p>Rows go in as SQL for {@link TransferListingTest}'s reason: a Transfer the application
 * can produce is {@code PENDING} and nothing else yet, and the status is half of what this
 * endpoint exists to report.
 */
class OneTransferByIdTest extends BootedApplicationTest {

	private static final String TRANSFERS = "/api/transfers";

	private static final long TRANSFER = 31L;

	private static final Instant REQUESTED_AT = Instant.parse("2026-09-06T09:41:00Z");

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void startFromTwoAccountsAndNoTransfers() {
		TransferRows.startFromTwoAccountsAndNoTransfers(jdbc);
	}

	@AfterEach
	void emptyTheTransferAndAccountTables() {
		TransferRows.empty(jdbc);
	}

	/**
	 * The same representation the listing sends, member for member. One shape for both
	 * endpoints is what lets ticket 32 generate a single Transfer type, and what makes the
	 * page a client lands on after submitting the same page it refreshes an hour later.
	 */
	@Test
	@DisplayName("a Transfer is fetched by its identifier, in the shape the listing sends")
	void fetchesOneTransferByItsIdentifier() {
		insertTransfer(TRANSFER, TransferStatus.SETTLED,
				new Money(120_00L, Currency.EUR), new Money(46_800L, Currency.HUF));

		client().get().uri(TRANSFERS + "/" + TRANSFER)
				.exchange()
				.expectStatus().isOk()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
				.expectBody()
				.jsonPath("$.id").isEqualTo(TRANSFER)
				.jsonPath("$.fromAccountId").isEqualTo(SOURCE_ACCOUNT)
				.jsonPath("$.toAccountId").isEqualTo(DESTINATION_ACCOUNT)
				.jsonPath("$.status").isEqualTo("SETTLED")
				.jsonPath("$.debitedAmountMinorUnits").isEqualTo(120_00L)
				.jsonPath("$.debitedAmountCurrency").isEqualTo("EUR")
				.jsonPath("$.creditedAmountMinorUnits").isEqualTo(46_800L)
				.jsonPath("$.creditedAmountCurrency").isEqualTo("HUF")
				.jsonPath("$.createdAt").isEqualTo(REQUESTED_AT.toString());
	}

	/**
	 * {@code 404} here is the meaning ticket 05 settled for it — <em>the path names
	 * nothing</em> — rather than a second meaning for the status. Ticket 14 answers an unknown
	 * Account ID inside a Transfer's payload with {@code 422} precisely so that this one stays
	 * unambiguous: there, {@code /api/transfers} existed and the body was unprocessable; here,
	 * the identifier <em>is</em> the path.
	 */
	@Test
	@DisplayName("an identifier no Transfer has is a not-found problem document")
	void refusesAnIdentifierNoTransferHas() {
		client().get().uri(TRANSFERS + "/4242")
				.exchange()
				.expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.type").isEqualTo(ProblemType.NOT_FOUND.urn())
				.jsonPath("$.title").isNotEmpty()
				.jsonPath("$.detail").isNotEmpty()
				.jsonPath("$.status").isEqualTo(404)
				.jsonPath("$.instance").isEqualTo(TRANSFERS + "/4242")
				.jsonPath("$.transferId").isEqualTo(4242);
	}

	/**
	 * An identifier of the wrong <em>kind</em> is a rejected value rather than a missing
	 * resource, and it is reported against {@code id} in the same shape a rejected body field
	 * gets. Answering {@code 404} instead would be defensible and would cost the caller the
	 * one thing this document tells them: that no amount of retrying that URL will help.
	 */
	@Test
	@DisplayName("an identifier that is not a number is a rejected value, named as one")
	void refusesAnIdentifierThatIsNotANumber() {
		client().get().uri(TRANSFERS + "/not-a-number")
				.exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.type").isEqualTo(ProblemType.VALIDATION_FAILED.urn())
				.jsonPath("$.errors[*].field")
				.value((List<String> fields) -> assertThat(fields).containsExactly("id"))
				.jsonPath("$.errors[0].message").isEqualTo("must be a whole number");
	}

	/** Every Transfer this class needs was requested at the same instant. */
	private void insertTransfer(long id, TransferStatus status, Money debitedAmount, Money creditedAmount) {
		TransferRows.insertTransfer(jdbc, id, status, REQUESTED_AT, debitedAmount, creditedAmount);
	}
}
