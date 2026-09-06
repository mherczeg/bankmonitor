package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.accounts.InsufficientFundsException;
import hu.bankmonitor.payments.accounts.UnknownAccountException;
import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import hu.bankmonitor.payments.common.ProblemType;
import hu.bankmonitor.payments.fx.ExchangeRateUnavailableException;
import hu.bankmonitor.payments.idempotency.IdempotencyKeyReusedException;
import hu.bankmonitor.payments.idempotency.IdempotentExecution;
import hu.bankmonitor.payments.idempotency.RequestInProgressException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;

/**
 * What an API client posts to request a Transfer, and what comes back.
 *
 * <p>No database: {@link FundsReservation} is stubbed, so every assertion here is about the
 * wire. That the same request reserves funds and reaches the tables is
 * {@link ReservedFundsReachTheTableTest}'s claim.
 */
@WebMvcTest(TransferController.class)
@AutoConfigureMockMvc(addFilters = false)
class TransferRequestContractTest {

	private static final Instant REQUESTED_AT = Instant.parse("2026-09-06T09:41:00Z");

	/**
	 * Later than the request it prices, which is the order the application actually produces:
	 * {@code createdAt} is stamped from the injected clock at the top of the controller method,
	 * before the Idempotency Key is even claimed, and the quote comes back from the provider
	 * some way into phase two. The intuitive reading — a rate is fetched, then the Transfer it
	 * prices is written — is wrong about which instant {@code createdAt} holds.
	 *
	 * <p>The gap itself is what matters: a fixture where the two agreed would let a response
	 * that reported the wrong one of the two timestamps still pass. {@code TransferRows} places
	 * its rows to the same ordering.
	 */
	private static final Instant RATE_FETCHED_AT = Instant.parse("2026-09-06T09:41:02Z");

	private static final String IDEMPOTENCY_KEY = "0d1f6c1e-6b0a-4a5f-9f1a-2c3d4e5f6a7b";

	private static final String VALID_PAYLOAD = """
			{"fromAccountId": 5, "toAccountId": 9, "amountMinorUnits": 10050}""";

	@Autowired
	private MockMvcTester mvc;

	@MockitoBean
	private TransferQuotes quotes;

	@MockitoBean
	private FundsReservation reservation;

	@MockitoBean
	private TransferLookup transfers;

	@MockitoBean
	private Clock clock;

	@MockitoBean
	private IdempotentExecution requests;

	@BeforeEach
	void fixTheInstantTheTransferIsRequestedAt() {
		given(clock.instant()).willReturn(REQUESTED_AT);
	}

	/**
	 * A first request for every key, unless a test says otherwise: the port resolves what it
	 * was handed, runs the operation over the result and returns what that answered. Without
	 * it every assertion in this class about the {@code 201} would be made against the {@code
	 * null} an unstubbed mock returns, which is not what an endpoint with no duplicate to
	 * resolve does.
	 *
	 * <p>Both phases are run here rather than only the operation, because the order they run
	 * in is the port's contract and this stub stands in for it. A stub that skipped the
	 * resolution would let the endpoint pass while handing phase three something it never
	 * quoted.
	 *
	 * <p>The tests that replace this stubbing use {@code willX().given(mock)} rather
	 * than {@code given(mock.x())}, because the second form <em>calls</em> the mock to
	 * record what to stub — and calling it here runs the answer above against the null
	 * arguments the matchers stand in for.
	 */
	@BeforeEach
	void runWhateverTheEndpointHandsToTheIdempotentPort() {
		given(requests.executeOnce(any(), any(), any(), any(), any()))
				.willAnswer(call -> call.getArgument(4, Function.class)
						.apply(call.getArgument(3, Supplier.class).get()));
	}

	@Test
	@DisplayName("requesting a Transfer answers 201 with the PENDING Transfer it created")
	void answersCreatedWithThePendingTransferItRequested() {
		ReservationRequest expected = new ReservationRequest(5L, 9L, 100_50L, REQUESTED_AT);
		stubBothPhases(31L, expected, sameCurrency(expected, Currency.EUR));

		MvcTestResult result = request("""
				{"fromAccountId": 5, "toAccountId": 9, "amountMinorUnits": 10050}""");

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
		assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(31);
		assertThat(result).bodyJson().extractingPath("$.fromAccountId").isEqualTo(5);
		assertThat(result).bodyJson().extractingPath("$.toAccountId").isEqualTo(9);
		assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
		assertThat(result).bodyJson().extractingPath("$.createdAt").isEqualTo(REQUESTED_AT.toString());
	}

	/**
	 * Ticket 14 left the {@code Location} header off deliberately, because the resource it
	 * would have addressed did not exist and a header pointing at a {@code 404} is worse than
	 * its absence. Ticket 15 builds that resource, so the header arrives with it — the order
	 * {@code docs/deferred.md} names for the Account's missing endpoint, applied here.
	 *
	 * <p>It is asserted against the identifier in the body rather than a literal, because the
	 * two agreeing is the whole claim: a header a client can follow to the Transfer it just
	 * created.
	 */
	@Test
	@DisplayName("the 201 points at the created Transfer's own URL")
	void pointsAtTheCreatedTransfersOwnUrl() {
		ReservationRequest expected = new ReservationRequest(5L, 9L, 100_50L, REQUESTED_AT);
		stubBothPhases(31L, expected, sameCurrency(expected, Currency.EUR));

		MvcTestResult result = request(VALID_PAYLOAD);

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).hasHeader(HttpHeaders.LOCATION, "/api/transfers/31");
	}

	/**
	 * The Currency is never in the payload — it is the source Account's, and only the two
	 * phases behind this endpoint have read that Account. What the endpoint hands on is
	 * therefore a bare count of Minor Units, and both amounts come back denominated.
	 */
	@Test
	@DisplayName("the Currency is derived from the source Account rather than sent by the client")
	void derivesTheCurrencyFromTheSourceAccount() {
		ReservationRequest expected = new ReservationRequest(5L, 9L, 90_000L, REQUESTED_AT);
		ConvertedAmounts amounts = sameCurrency(expected, Currency.HUF);
		stubBothPhases(4L, expected, amounts);

		MvcTestResult result = request("""
				{"fromAccountId": 5, "toAccountId": 9, "amountMinorUnits": 90000}""");

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.debitedAmountMinorUnits").isEqualTo(90000);
		assertThat(result).bodyJson().extractingPath("$.debitedAmountCurrency").isEqualTo("HUF");
		assertThat(result).bodyJson().extractingPath("$.creditedAmountMinorUnits").isEqualTo(90000);
		assertThat(result).bodyJson().extractingPath("$.creditedAmountCurrency").isEqualTo("HUF");
		then(reservation).should().reserve(expected, amounts);
	}

	/**
	 * A Transfer across two Currencies, end to end over the wire: the destination is credited
	 * in its own Currency and the rate that got it there is reported with the moment it was
	 * quoted, so a settled conversion can be checked rather than taken on trust.
	 *
	 * <p>HUF on the credited side because its Minor Units are not hundredths, so a figure
	 * that had picked up or lost a division by a hundred would still read plausibly in EUR
	 * and does not here.
	 */
	@Test
	@DisplayName("a cross-Currency Transfer reports both amounts, the rate and when it was fetched")
	void reportsTheRateACrossCurrencyTransferWasConvertedAt() {
		ReservationRequest expected = new ReservationRequest(5L, 9L, 100_50L, REQUESTED_AT);
		stubBothPhases(31L, expected, new ConvertedAmounts(
				new Money(100_50L, Currency.EUR), new Money(39_698L, Currency.HUF),
				new BigDecimal("395.000000"), RATE_FETCHED_AT));

		MvcTestResult result = request(VALID_PAYLOAD);

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.debitedAmountMinorUnits").isEqualTo(10050);
		assertThat(result).bodyJson().extractingPath("$.debitedAmountCurrency").isEqualTo("EUR");
		assertThat(result).bodyJson().extractingPath("$.creditedAmountMinorUnits").isEqualTo(39698);
		assertThat(result).bodyJson().extractingPath("$.creditedAmountCurrency").isEqualTo("HUF");
		assertThat(result).bodyJson().extractingPath("$.exchangeRate").convertTo(BIG_DECIMAL)
				.isEqualByComparingTo("395.000000");
		assertThat(result).bodyJson().extractingPath("$.exchangeRateFetchedAt")
				.isEqualTo(RATE_FETCHED_AT.toString());
	}

	/**
	 * The two members are <em>absent</em> rather than present and null, which is the contract
	 * the published schema and the generated client both state: no provider was asked, so there
	 * is no quote to report, and a rate of {@code 1} would be this API describing a fetch that
	 * never happened.
	 *
	 * <p>Absence is what {@code @JsonInclude(NON_NULL)} on {@link TransferResponse} buys, and
	 * {@code doesNotHavePath} is what can see it. The neighbouring test asserts the other half —
	 * that the members are there, with the quote in them, when there was one.
	 */
	@Test
	@DisplayName("a same-Currency Transfer reports no rate at all")
	void reportsNoRateForATransferThatNeededNone() {
		ReservationRequest expected = new ReservationRequest(5L, 9L, 100_50L, REQUESTED_AT);
		stubBothPhases(31L, expected, sameCurrency(expected, Currency.EUR));

		MvcTestResult result = request(VALID_PAYLOAD);

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().doesNotHavePath("$.exchangeRate");
		assertThat(result).bodyJson().doesNotHavePath("$.exchangeRateFetchedAt");
	}

	/**
	 * The key is required from this endpoint's first version, and does nothing yet: the
	 * guarantee behind it is ticket 17's. Requiring it now is what stops a client ever
	 * being written against a version that let them opt out, which is the version that
	 * would still be in production when the guarantee arrives.
	 */
	@Nested
	@DisplayName("the Idempotency Key")
	class IdempotencyKey {

		@Test
		@DisplayName("is required, and a request without one never reaches the reservation")
		void isRequired() {
			MvcTestResult result = requestWithNoKey(VALID_PAYLOAD).exchange();

			assertThatNamesARejectedField(result, "idempotencyKey");
			then(reservation).shouldHaveNoInteractions();
		}

		/**
		 * A key that is not a UUID is refused rather than accepted as an opaque string.
		 * Keys are globally scoped, so the only thing standing between a caller and
		 * squatting on someone else's key is that guessing one is a 122-bit problem —
		 * which is a property of UUIDs and not of arbitrary text.
		 */
		@Test
		@DisplayName("has to be a well-formed UUID")
		void hasToBeAWellFormedUuid() {
			MvcTestResult result = requestWithKey("not-a-uuid", VALID_PAYLOAD);

			assertThatNamesARejectedField(result, "idempotencyKey");
			then(reservation).shouldHaveNoInteractions();
		}

		/**
		 * Well-formed is the whole of the rule: any version, either letter case. Both of
		 * these are keys a client library hands out today — {@code uuidv7} is the default
		 * in several, and a key that has been through an uppercasing log or an HTTP client
		 * that normalises headers is still the same 122 bits.
		 *
		 * <p>Hibernate Validator's {@code @UUID} defaults to {@code version = {1, 2, 3, 4,
		 * 5}} and {@code letterCase = LOWER_CASE}, so both of these are {@code 400} under
		 * the bare annotation — an annotation named for the format accepting less than the
		 * format.
		 */
		@ParameterizedTest(name = "{0} is accepted")
		@ValueSource(strings = {
				"0D1F6C1E-6B0A-4A5F-9F1A-2C3D4E5F6A7B",
				"019249a4-8f3c-7c2e-b1d5-9e6f0a1b2c3d"})
		@DisplayName("is any well-formed UUID, whatever its version or letter case")
		void acceptsAnyWellFormedUuid(String idempotencyKey) {
			ReservationRequest expected = new ReservationRequest(5L, 9L, 100_50L, REQUESTED_AT);
			stubBothPhases(31L, expected, sameCurrency(expected, Currency.EUR));

			MvcTestResult result = requestWithKey(idempotencyKey, VALID_PAYLOAD);

			assertThat(result).hasStatus(HttpStatus.CREATED);
		}

		/**
		 * The one well-formed key that is refused, and for the reason above rather than
		 * despite it: the all-zero UUID is the value every client that forgot to generate
		 * one arrives with, so it is the one key two callers would collide on.
		 */
		@Test
		@DisplayName("is not the nil UUID, which is the one key a caller can guess")
		void refusesTheNilUuid() {
			MvcTestResult result = requestWithKey("00000000-0000-0000-0000-000000000000", VALID_PAYLOAD);

			assertThatNamesARejectedField(result, "idempotencyKey");
			then(reservation).shouldHaveNoInteractions();
		}
	}

	/**
	 * The endpoint's half of design decision 5: it hands the key, what the request said and
	 * the operation to the port, and answers whatever comes back — including the two
	 * refusals, which are the only ones here that are not {@code 422}.
	 *
	 * <p>The two {@code 409}s share a status and mean opposite things, so both halves of
	 * what tells them apart are asserted every time: the {@code type} URN a client branches
	 * on, and the {@code Retry-After} that says whether coming back can ever help.
	 */
	@Nested
	@DisplayName("a repeat of an Idempotency Key")
	class Repeats {

		/**
		 * The guarantee as a client sees it. The response is the first request's, so the
		 * {@code Location} still points at the Transfer that already exists — and the
		 * reservation is never reached, which is the half that says no second Transfer was
		 * created rather than merely that none was reported.
		 *
		 * <p>No Check Ledger on the replayed response, and none on the first request's either:
		 * {@code checks} is what {@code GET /api/transfers/{id}} adds, and the {@code Location}
		 * header above is the client's way to it.
		 */
		@Test
		@DisplayName("replays the first request's 201 without reserving anything again")
		void replaysTheFirstRequestsResponse() {
			willReturn(new TransferResponse(31L, 5L, 9L, TransferStatus.PENDING,
					100_50L, Currency.EUR, 100_50L, Currency.EUR, null, null, REQUESTED_AT, null))
					.given(requests).executeOnce(any(), any(), eq(TransferResponse.class), any(), any());

			MvcTestResult result = request(VALID_PAYLOAD);

			assertThat(result).hasStatus(HttpStatus.CREATED);
			assertThat(result).hasHeader(HttpHeaders.LOCATION, "/api/transfers/31");
			assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(31);
			then(reservation).shouldHaveNoInteractions();
		}

		/**
		 * Retryable, and the header is what says so. A client meeting this one has sent a
		 * request that may still succeed under the very key it is holding, so the only
		 * correct action is to wait and send it again.
		 */
		@Test
		@DisplayName("whose first request has not finished is 409 with Retry-After")
		void refusesAKeyWhoseWorkIsUnfinished() {
			willThrow(new RequestInProgressException(IDEMPOTENCY_KEY))
					.given(requests).executeOnce(any(), any(), any(), any(), any());

			MvcTestResult result = request(VALID_PAYLOAD);

			assertThatIsAConflict(result, ProblemType.REQUEST_IN_PROGRESS);
			assertThat(result).headers().hasSingleValue(HttpHeaders.RETRY_AFTER, "1");
			then(reservation).shouldHaveNoInteractions();
		}

		/**
		 * The refusal a client must never repeat, and the absent header is what says so.
		 * Retrying cannot make two payloads agree, so a {@code Retry-After} here would
		 * invite a loop that can only ever be refused again.
		 */
		@Test
		@DisplayName("carrying a different payload is 409 with no Retry-After")
		void refusesAKeyReusedForADifferentPayload() {
			willThrow(new IdempotencyKeyReusedException(IDEMPOTENCY_KEY))
					.given(requests).executeOnce(any(), any(), any(), any(), any());

			MvcTestResult result = request(VALID_PAYLOAD);

			assertThatIsAConflict(result, ProblemType.IDEMPOTENCY_KEY_REUSED);
			assertThat(result).doesNotContainHeader(HttpHeaders.RETRY_AFTER);
			then(reservation).shouldHaveNoInteractions();
		}

		/**
		 * What the port is given to decide with. The hash is the parsed request's own, so a
		 * second posting of the same Transfer produces the same value however its JSON was
		 * spelled — which is why this one is posted with its members reordered and padded.
		 */
		@Test
		@DisplayName("is decided from the key and what the request said, not from the bytes that carried it")
		void handsThePortTheKeyAndThePayloadsHash() {
			request("""
					{"amountMinorUnits": 10050,   "toAccountId": 9, "fromAccountId": 5}""");

			then(requests).should().executeOnce(eq(IDEMPOTENCY_KEY),
					eq(new CreateTransferRequest(5L, 9L, 100_50L).payloadHash()),
					eq(TransferResponse.class), any(), any());
		}
	}

	/**
	 * A Transfer of nothing is not a smaller Transfer, and one of a negative amount is a
	 * Transfer in the other direction wearing the wrong Accounts. Both are refused against
	 * the field that carried them rather than reaching the reservation, where the source
	 * Account's Reserved Amount would move the wrong way.
	 */
	@ParameterizedTest(name = "an amount of {0} Minor Units is refused")
	@ValueSource(longs = {0L, -1L})
	void refusesANonPositiveAmount(long amountMinorUnits) {
		MvcTestResult result = request("""
				{"fromAccountId": 5, "toAccountId": 9, "amountMinorUnits": %d}""".formatted(amountMinorUnits));

		assertThatNamesARejectedField(result, "amountMinorUnits");
		then(reservation).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("an empty request names all three missing fields, not just the first")
	void namesEveryMissingField() {
		MvcTestResult result = request("{}");

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson()
				.extractingPath("$.errors[*].field").asInstanceOf(LIST)
				.containsExactly("amountMinorUnits", "fromAccountId", "toAccountId");
		then(reservation).shouldHaveNoInteractions();
	}

	/**
	 * The refusals that cannot be decided from the payload alone, each arriving from one of
	 * the two phases behind the endpoint as its own exception and leaving as its own
	 * {@code type} URN. The status is {@code 422} throughout: every one of them is a
	 * well-formed request this API understood and would not carry out, which is a different
	 * thing from the {@code 400}s above.
	 *
	 * <p>Which phase raised one is deliberately invisible from here. A conversion that rounds
	 * to zero is decided above the lock and an overdraft under it, and a client has no use for
	 * that distinction — what it branches on is the URN, because a client that had to read
	 * {@code detail} to know which of the four it met would be parsing prose.
	 */
	@Nested
	@DisplayName("a refusal from behind the endpoint")
	class Refusals {

		@Test
		@DisplayName("names the Account a Transfer to itself was asked for")
		void namesTheAccountOfASelfTransfer() {
			given(reservation.reserve(any(), any())).willThrow(new SelfTransferNotAllowedException(5L));

			MvcTestResult result = request("""
					{"fromAccountId": 5, "toAccountId": 5, "amountMinorUnits": 10050}""");

			assertThatIsARefusal(result, ProblemType.SELF_TRANSFER);
			assertThat(result).bodyJson().extractingPath("$.accountId").isEqualTo(5);
		}

		/**
		 * A Transfer has two Accounts, so "one of them does not exist" is not an answer a
		 * client can act on. The ID travels with the refusal for that reason alone.
		 */
		@Test
		@DisplayName("says which of the two Accounts does not exist")
		void saysWhichAccountDoesNotExist() {
			given(reservation.reserve(any(), any())).willThrow(new UnknownAccountException(9L));

			MvcTestResult result = request(VALID_PAYLOAD);

			assertThatIsARefusal(result, ProblemType.UNKNOWN_ACCOUNT);
			assertThat(result).bodyJson().extractingPath("$.accountId").isEqualTo(9);
		}

		/**
		 * Both figures that were compared, because neither survives the refusal: the
		 * transaction that read the Available Balance has rolled back by the time the client
		 * sees this, so a client that wanted to show the shortfall would otherwise have to
		 * re-read an Account that may have moved again.
		 */
		@Test
		@DisplayName("reports both amounts an overdraft check compared")
		void reportsBothAmountsTheOverdraftCheckCompared() {
			given(reservation.reserve(any(), any())).willThrow(new InsufficientFundsException(
					new Money(40_00L, Currency.EUR), new Money(100_50L, Currency.EUR)));

			MvcTestResult result = request(VALID_PAYLOAD);

			assertThatIsARefusal(result, ProblemType.INSUFFICIENT_FUNDS);
			assertThat(result).bodyJson().extractingPath("$.availableBalanceMinorUnits").isEqualTo(4000);
			assertThat(result).bodyJson().extractingPath("$.requestedAmountMinorUnits").isEqualTo(10050);
			assertThat(result).bodyJson().extractingPath("$.currency").isEqualTo("EUR");
		}

		/**
		 * Refused rather than written, and everything the conversion compared travels with the
		 * refusal: the amount, what it was going into, and the rate that made it nothing. An
		 * operator's remedy is to send more, and a refusal that named none of the three would
		 * not tell them how much more.
		 *
		 * <p>The rate is asserted by comparing numbers rather than strings, because a
		 * {@code BigDecimal} serialised through JSON keeps its scale and {@code 390.000000} and
		 * {@code 390.0} are the same rate.
		 */
		@Test
		@DisplayName("names everything a conversion that rounded to zero compared")
		void namesEverythingAConversionToZeroCompared() {
			given(quotes.convert(any())).willThrow(new ConversionRoundsToZeroException(
					new Money(1L, Currency.HUF), Currency.EUR, new BigDecimal("0.002564")));

			MvcTestResult result = request(VALID_PAYLOAD);

			assertThatIsARefusal(result, ProblemType.CONVERSION_ROUNDS_TO_ZERO);
			assertThat(result).bodyJson().extractingPath("$.debitedAmountMinorUnits").isEqualTo(1);
			assertThat(result).bodyJson().extractingPath("$.debitedAmountCurrency").isEqualTo("HUF");
			assertThat(result).bodyJson().extractingPath("$.destinationCurrency").isEqualTo("EUR");
			assertThat(result).bodyJson().extractingPath("$.exchangeRate").convertTo(BIG_DECIMAL)
					.isEqualByComparingTo("0.002564");
			then(reservation).shouldHaveNoInteractions();
		}
	}

	/**
	 * The one failure this endpoint answers that is nobody's fault on this side of the wire,
	 * and so the one that is neither a {@code 4xx} nor an accident: a {@code 503} saying the
	 * request was fine and a third party was not.
	 *
	 * <p>Asserted apart from {@link #assertThatIsARefusal}, which fixes the status at
	 * {@code 422} — the point of this one is that it is not one of those.
	 */
	@Test
	@DisplayName("a provider that never answered is 503 with a Retry-After and what was asked of it")
	void reportsAnUnpricedTransferAsAnOutageWorthRetrying() {
		given(quotes.convert(any())).willThrow(
				new ExchangeRateUnavailableException(Currency.EUR, Currency.HUF, 3, new RuntimeException("timeout")));

		MvcTestResult result = request(VALID_PAYLOAD);

		assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.type")
				.isEqualTo(ProblemType.FX_PROVIDER_UNAVAILABLE.urn());
		assertThat(result).bodyJson().extractingPath("$.title").isNotNull();
		assertThat(result).bodyJson().extractingPath("$.detail").asString().isNotBlank();
		assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(503);
		assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/api/transfers");
		assertThat(result).headers().hasSingleValue(HttpHeaders.RETRY_AFTER, "5");
		assertThat(result).bodyJson().extractingPath("$.baseCurrency").isEqualTo("EUR");
		assertThat(result).bodyJson().extractingPath("$.quoteCurrency").isEqualTo("HUF");
		assertThat(result).bodyJson().extractingPath("$.attempts").isEqualTo(3);
		then(reservation).shouldHaveNoInteractions();
	}

	private MvcTestResult request(String body) {
		return requestWithKey(IDEMPOTENCY_KEY, body);
	}

	private MvcTestResult requestWithKey(String idempotencyKey, String body) {
		return requestWithNoKey(body).header("X-Idempotency-Key", idempotencyKey).exchange();
	}

	private MockMvcTester.MockMvcRequestBuilder requestWithNoKey(String body) {
		return mvc.post().uri("/api/transfers")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	/**
	 * One shape and one discriminator, whichever refusal it is — the same claim
	 * {@code ProblemDocumentContractTest} makes for the framework's own failures, made here
	 * for the ones this slice raises deliberately.
	 */
	private static void assertThatIsARefusal(MvcTestResult result, ProblemType expected) {
		assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
		assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(expected.urn());
		assertThat(result).bodyJson().extractingPath("$.title").isNotNull();
		assertThat(result).bodyJson().extractingPath("$.detail").asString().isNotBlank();
		assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(422);
		assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/api/transfers");
	}

	/**
	 * The two refusals that share {@code 409} and disagree about everything else. The status
	 * is asserted here and the header at each call site, because the header is the one part
	 * they do not share.
	 */
	private static void assertThatIsAConflict(MvcTestResult result, ProblemType expected) {
		assertThat(result).hasStatus(HttpStatus.CONFLICT);
		assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(expected.urn());
		assertThat(result).bodyJson().extractingPath("$.title").isNotNull();
		assertThat(result).bodyJson().extractingPath("$.detail").asString().isNotBlank();
		assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(409);
		assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/api/transfers");
	}

	/**
	 * A refusal a client can mark up against the input that caused it: the one problem
	 * document of design decision 18, with an entry naming what was rejected. The shape
	 * does not depend on whether the bad value arrived in the body or in a header, which is
	 * a distinction no client can see well enough to branch on.
	 */
	private static void assertThatNamesARejectedField(MvcTestResult result, String field) {
		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemType.VALIDATION_FAILED.urn());
		assertThat(result).bodyJson()
				.extractingPath("$.errors[*].field").asInstanceOf(LIST)
				.containsExactly(field);
		assertThat(result).bodyJson()
				.extractingPath("$.errors[0].message").asString().isNotBlank();
	}

	/**
	 * The identifier is the database's to hand out, so a Transfer that has one is one that
	 * has been saved — the state this test has to stand in for.
	 */
	private static Transfer pendingTransfer(long id, ReservationRequest request, ConvertedAmounts amounts) {
		Transfer transfer = new Transfer(request.sourceAccountId(), request.destinationAccountId(),
				amounts, request.requestedAt());
		ReflectionTestUtils.setField(transfer, "id", id);
		return transfer;
	}

	/**
	 * Both phases stubbed for one request, because the endpoint reaches them in order and a
	 * test that stubbed only the second would be asserting against the {@code null} the first
	 * one's mock hands back.
	 *
	 * <p>The same {@link ConvertedAmounts} instance goes into the reservation's stubbing as
	 * comes out of the quote's, which is the wiring claim: what phase two resolved is what
	 * phase three is given.
	 */
	private Transfer stubBothPhases(long id, ReservationRequest request, ConvertedAmounts amounts) {
		Transfer transfer = pendingTransfer(id, request, amounts);
		given(quotes.convert(request)).willReturn(amounts);
		given(reservation.reserve(request, amounts)).willReturn(transfer);
		return transfer;
	}

	/** A Transfer between two Accounts in one Currency, which is most of the cases here. */
	private static ConvertedAmounts sameCurrency(ReservationRequest request, Currency currency) {
		return ConvertedAmounts.unconverted(new Money(request.amountMinorUnits(), currency));
	}
}
