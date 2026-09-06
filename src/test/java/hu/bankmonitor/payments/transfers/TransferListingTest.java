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

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static hu.bankmonitor.testsupport.TransferRows.DESTINATION_ACCOUNT;
import static hu.bankmonitor.testsupport.TransferRows.SOURCE_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/transfers}: every Transfer in every status, newest first, narrowable to
 * one status.
 *
 * <p>Through the running server rather than a {@code @WebMvcTest} with a mocked service,
 * because the claim worth making is about statuses <em>no layer can write</em>. A Transfer
 * comes into being {@code PENDING} and design decision 11 leaves each advance to the ticket
 * that has a caller for it, so a mocked service would have to be handed Transfers the
 * application cannot produce and would then assert the order the test author had chosen.
 * The rows therefore go in as SQL, as they do in {@link TransferRoundTripsInEveryStatusTest}
 * for the same reason.
 *
 * <p>The context is the one {@link BootedApplicationTest} already boots for the suite, so
 * the reach costs nothing. The price is that this class writes to a database every other
 * test shares, which {@link #emptyTheTransferAndAccountTables()} pays back either side of
 * every method.
 */
class TransferListingTest extends BootedApplicationTest {

	private static final String LISTING = "/api/transfers";

	private static final String OPENAPI_DOCUMENT = "/v3/api-docs";

	private static final Instant EARLIEST = Instant.parse("2026-09-06T09:00:00Z");

	private static final Instant LATER = Instant.parse("2026-09-06T10:00:00Z");

	private static final Instant LATEST = Instant.parse("2026-09-06T11:00:00Z");

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
	 * Design decision 19's reading of the requirement, as an assertion: a {@code PENDING}
	 * Transfer appears on the only list screen there is. Filtered out, an operator whose
	 * Transfer is still waiting on its Checks would be told it had never happened.
	 */
	@Test
	@DisplayName("every Transfer is listed, whichever of the four statuses it is in")
	void listsTransfersInEveryStatus() {
		TransferStatus[] everyStatus = TransferStatus.values();
		for (int i = 0; i < everyStatus.length; i++) {
			insertTransfer(11L + i, everyStatus[i], LATER);
		}

		client().get().uri(LISTING)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.length()").isEqualTo(TransferStatus.values().length)
				.jsonPath("$[*].status")
				.value((List<String> statuses) -> assertThat(statuses)
						.containsExactlyInAnyOrder("PENDING", "SETTLED", "REJECTED", "EXPIRED"));
	}

	/**
	 * Newest first, and the tie is broken by identifier so that the order is total. Two
	 * Transfers requested in the same instant is not a contrivance — the deadline of design
	 * decision 14's {@code Clock} is read once per request and {@code created_at} has
	 * microsecond resolution — and an order that left them free to swap would let the
	 * Transactions screen reshuffle itself between two refetches of unchanged data.
	 */
	@Test
	@DisplayName("the newest Transfer is first, and Transfers of one instant are still ordered")
	void listsTheNewestTransferFirst() {
		insertTransfer(11L, TransferStatus.SETTLED, LATER);
		insertTransfer(12L, TransferStatus.PENDING, LATEST);
		insertTransfer(13L, TransferStatus.EXPIRED, EARLIEST);
		insertTransfer(14L, TransferStatus.REJECTED, LATEST);

		client().get().uri(LISTING)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$[*].id")
				.value((List<Integer> ids) -> assertThat(ids).containsExactly(14, 12, 11, 13));
	}

	@Test
	@DisplayName("the status filter narrows the listing to one status")
	void narrowsTheListingToOneStatus() {
		insertTransfer(11L, TransferStatus.SETTLED, EARLIEST);
		insertTransfer(12L, TransferStatus.PENDING, LATER);
		insertTransfer(13L, TransferStatus.SETTLED, LATEST);

		client().get().uri(LISTING + "?status=SETTLED")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.length()").isEqualTo(2)
				.jsonPath("$[*].id")
				.value((List<Integer> ids) -> assertThat(ids).containsExactly(13, 11));
	}

	/** A filter that matches nothing is an empty list: the collection exists and is empty. */
	@Test
	@DisplayName("a status nothing is in narrows the listing to nothing")
	void narrowsToAnEmptyListingWhenNothingIsInThatStatus() {
		insertTransfer(11L, TransferStatus.PENDING, LATER);

		client().get().uri(LISTING + "?status=EXPIRED")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$").isArray()
				.jsonPath("$.length()").isEqualTo(0);
	}

	/**
	 * A status this domain has no name for is a rejected value reported against the
	 * parameter that carried it, in the same document shape a rejected body field gets — so
	 * a client has one way to read every rejection rather than one per place a value can
	 * arrive. The message lists the statuses, which is the whole of what a closed set has
	 * to say about itself.
	 */
	@Test
	@DisplayName("a status this domain has no name for is refused, and the refusal names the set")
	void refusesAStatusThisDomainHasNoNameFor() {
		client().get().uri(LISTING + "?status=IN_FLIGHT")
				.exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.type").isEqualTo(ProblemType.VALIDATION_FAILED.urn())
				.jsonPath("$.errors[*].field")
				.value((List<String> fields) -> assertThat(fields).containsExactly("status"))
				.jsonPath("$.errors[0].message")
				.isEqualTo("must be one of: PENDING, SETTLED, REJECTED, EXPIRED");
	}

	/**
	 * {@code ?status=} with nothing after it is <em>no filter</em> rather than a rejected
	 * value. Spring's enum conversion treats an empty string as an absent value and hands the
	 * handler a {@code null}, and that is the reading kept here: a form that submits its
	 * fields whether or not the user touched them sends exactly this, and answering it with a
	 * {@code 400} would make an untouched dropdown an error.
	 *
	 * <p>It is asserted rather than left to the framework because it is a contract a client
	 * can be written against, and nothing in the code above states it.
	 */
	@Test
	@DisplayName("a status parameter with no value is no filter, not a rejected one")
	void treatsAnEmptyStatusParameterAsNoFilter() {
		insertTransfer(11L, TransferStatus.PENDING, LATER);
		insertTransfer(12L, TransferStatus.SETTLED, LATEST);

		client().get().uri(LISTING + "?status=")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.length()").isEqualTo(2);
	}

	/** No filter at all is every Transfer, not none: the parameter is optional. */
	@Test
	@DisplayName("an empty table is an empty list rather than a missing one")
	void listsNothingWhenNoTransferHasBeenRequested() {
		client().get().uri(LISTING)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$").isArray()
				.jsonPath("$.length()").isEqualTo(0);
	}

	/**
	 * Each amount is a whole count of Minor Units and carries the Currency it is counted
	 * in. The row is denominated differently on its two sides, which is a Transfer ticket 26
	 * produces and this service cannot yet request — the table permits it, as
	 * {@link TransferRoundTripsInEveryStatusTest} also relies on — and it is the only kind
	 * of row on which a shape that had collapsed the two Currencies into one would be caught.
	 *
	 * <p>HUF on the credited side for a second reason: its Minor Units are not hundredths,
	 * so a figure that had picked up a division by a hundred between the column and the wire
	 * would still read plausibly in EUR and would not here.
	 */
	@Test
	@DisplayName("each amount is a count of Minor Units in the Currency of its own side")
	void reportsEachAmountInTheCurrencyOfItsOwnSide() {
		insertTransfer(11L, TransferStatus.PENDING, LATER,
				new Money(120_00L, Currency.EUR), new Money(46_800L, Currency.HUF));

		client().get().uri(LISTING)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$[0].fromAccountId").isEqualTo(SOURCE_ACCOUNT)
				.jsonPath("$[0].toAccountId").isEqualTo(DESTINATION_ACCOUNT)
				.jsonPath("$[0].debitedAmountMinorUnits").isEqualTo(120_00L)
				.jsonPath("$[0].debitedAmountCurrency").isEqualTo("EUR")
				.jsonPath("$[0].creditedAmountMinorUnits").isEqualTo(46_800L)
				.jsonPath("$[0].creditedAmountCurrency").isEqualTo("HUF")
				.jsonPath("$[0].createdAt").isEqualTo(LATER.toString());
	}

	/**
	 * The shape ticket 32 generates the frontend's types from, and ticket 43's Transactions
	 * screen reads. springdoc derives {@code required} from constraint annotations and a
	 * response is never validated, so left alone this record would publish a schema whose
	 * every member is optional — and a mock omitting the status would still compile.
	 * {@link TransferResponse} says so itself, and the list is compared against the record's
	 * own components so the two cannot drift apart.
	 */
	@Test
	@DisplayName("the OpenAPI document describes both read endpoints and the response shape")
	void describesBothReadEndpointsInTheOpenApiDocument() {
		List<String> everyComponent = Arrays.stream(TransferResponse.class.getRecordComponents())
				.map(RecordComponent::getName)
				.toList();

		client().get().uri(OPENAPI_DOCUMENT)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.paths['" + LISTING + "'].get").exists()
				.jsonPath("$.paths['" + LISTING + "/{id}'].get").exists()
				.jsonPath("$.components.schemas.TransferResponse.properties.status.enum")
				.value((List<String> statuses) -> assertThat(statuses)
						.containsExactlyInAnyOrder("PENDING", "SETTLED", "REJECTED", "EXPIRED"))
				.jsonPath("$.components.schemas.TransferResponse.required")
				.value((List<String> required) ->
						assertThat(required).containsExactlyInAnyOrderElementsOf(everyComponent));
	}

	/** The amount is the same on both sides for every Transfer whose status or order is the claim. */
	private void insertTransfer(long id, TransferStatus status, Instant createdAt) {
		Money sameOnBothSides = new Money(100_00L, Currency.EUR);
		insertTransfer(id, status, createdAt, sameOnBothSides, sameOnBothSides);
	}

	private void insertTransfer(long id, TransferStatus status, Instant createdAt,
			Money debitedAmount, Money creditedAmount) {
		TransferRows.insertTransfer(jdbc, id, status, createdAt, debitedAmount, creditedAmount);
	}
}
