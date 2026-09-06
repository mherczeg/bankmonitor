package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.accounts.InsufficientFundsException;
import hu.bankmonitor.payments.accounts.UnknownAccountException;
import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import hu.bankmonitor.payments.common.ProblemType;
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

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
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

	private static final String IDEMPOTENCY_KEY = "0d1f6c1e-6b0a-4a5f-9f1a-2c3d4e5f6a7b";

	private static final String VALID_PAYLOAD = """
			{"fromAccountId": 5, "toAccountId": 9, "amountMinorUnits": 10050}""";

	@Autowired
	private MockMvcTester mvc;

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
	 * A first request for every key, unless a test says otherwise: the port runs the
	 * operation it was handed and returns what it answered. Without it every assertion in
	 * this class about the {@code 201} would be made against the {@code null} an unstubbed
	 * mock returns, which is not what an endpoint with no duplicate to resolve does.
	 *
	 * <p>The three tests that replace this stubbing use {@code willX().given(mock)} rather
	 * than {@code given(mock.x())}, because the second form <em>calls</em> the mock to
	 * record what to stub — and calling it here runs the answer above against the null
	 * arguments the matchers stand in for.
	 */
	@BeforeEach
	void runWhateverTheEndpointHandsToTheIdempotentPort() {
		given(requests.executeOnce(any(), any(), any(), any()))
				.willAnswer(call -> call.getArgument(3, Supplier.class).get());
	}

	@Test
	@DisplayName("requesting a Transfer answers 201 with the PENDING Transfer it created")
	void answersCreatedWithThePendingTransferItRequested() {
		ReservationRequest expected = new ReservationRequest(5L, 9L, 100_50L, REQUESTED_AT);
		given(reservation.reserve(expected)).willReturn(pendingTransfer(31L, expected, Currency.EUR));

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
		given(reservation.reserve(expected)).willReturn(pendingTransfer(31L, expected, Currency.EUR));

		MvcTestResult result = request(VALID_PAYLOAD);

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).hasHeader(HttpHeaders.LOCATION, "/api/transfers/31");
	}

	/**
	 * The Currency is never in the payload — it is the source Account's, and only the
	 * reservation has read that Account under its lock. What the endpoint hands on is
	 * therefore a bare count of Minor Units, and both amounts come back denominated.
	 */
	@Test
	@DisplayName("the Currency is derived from the source Account rather than sent by the client")
	void derivesTheCurrencyFromTheSourceAccount() {
		ReservationRequest expected = new ReservationRequest(5L, 9L, 90_000L, REQUESTED_AT);
		given(reservation.reserve(expected)).willReturn(pendingTransfer(4L, expected, Currency.HUF));

		MvcTestResult result = request("""
				{"fromAccountId": 5, "toAccountId": 9, "amountMinorUnits": 90000}""");

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.debitedAmountMinorUnits").isEqualTo(90000);
		assertThat(result).bodyJson().extractingPath("$.debitedAmountCurrency").isEqualTo("HUF");
		assertThat(result).bodyJson().extractingPath("$.creditedAmountMinorUnits").isEqualTo(90000);
		assertThat(result).bodyJson().extractingPath("$.creditedAmountCurrency").isEqualTo("HUF");
		then(reservation).should().reserve(expected);
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
			given(reservation.reserve(expected)).willReturn(pendingTransfer(31L, expected, Currency.EUR));

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
		 */
		@Test
		@DisplayName("replays the first request's 201 without reserving anything again")
		void replaysTheFirstRequestsResponse() {
			willReturn(new TransferResponse(31L, 5L, 9L, TransferStatus.PENDING,
					100_50L, Currency.EUR, 100_50L, Currency.EUR, REQUESTED_AT))
					.given(requests).executeOnce(any(), any(), eq(TransferResponse.class), any());

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
					.given(requests).executeOnce(any(), any(), any(), any());

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
					.given(requests).executeOnce(any(), any(), any(), any());

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
					eq(TransferResponse.class), any());
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
	 * The refusals that cannot be decided from the payload alone, each arriving from the
	 * reservation as its own exception and leaving as its own {@code type} URN. The status
	 * is {@code 422} throughout: every one of them is a well-formed request this API
	 * understood and would not carry out, which is a different thing from the {@code 400}s
	 * above.
	 *
	 * <p>Told apart by their URN and not by their status, because a client that had to read
	 * {@code detail} to know which of the four it met would be parsing prose.
	 */
	@Nested
	@DisplayName("a refusal from the reservation")
	class Refusals {

		@Test
		@DisplayName("names the Account a Transfer to itself was asked for")
		void namesTheAccountOfASelfTransfer() {
			given(reservation.reserve(any())).willThrow(new SelfTransferNotAllowedException(5L));

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
			given(reservation.reserve(any())).willThrow(new UnknownAccountException(9L));

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
			given(reservation.reserve(any())).willThrow(new InsufficientFundsException(
					new Money(40_00L, Currency.EUR), new Money(100_50L, Currency.EUR)));

			MvcTestResult result = request(VALID_PAYLOAD);

			assertThatIsARefusal(result, ProblemType.INSUFFICIENT_FUNDS);
			assertThat(result).bodyJson().extractingPath("$.availableBalanceMinorUnits").isEqualTo(4000);
			assertThat(result).bodyJson().extractingPath("$.requestedAmountMinorUnits").isEqualTo(10050);
			assertThat(result).bodyJson().extractingPath("$.currency").isEqualTo("EUR");
		}

		/**
		 * Refused rather than converted, and refused rather than written: until ticket 26
		 * fetches an Exchange Rate there is no figure to credit the destination Account
		 * with, and the Currency this endpoint can derive is the source Account's alone.
		 */
		@Test
		@DisplayName("names both Currencies of a Transfer this service cannot convert yet")
		void namesBothCurrenciesOfATransferItCannotConvert() {
			given(reservation.reserve(any()))
					.willThrow(new CrossCurrencyTransferNotSupportedException(Currency.EUR, Currency.HUF));

			MvcTestResult result = request(VALID_PAYLOAD);

			assertThatIsARefusal(result, ProblemType.CROSS_CURRENCY_UNSUPPORTED);
			assertThat(result).bodyJson().extractingPath("$.sourceCurrency").isEqualTo("EUR");
			assertThat(result).bodyJson().extractingPath("$.destinationCurrency").isEqualTo("HUF");
		}
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
	private static Transfer pendingTransfer(long id, ReservationRequest request, Currency currency) {
		Money amount = new Money(request.amountMinorUnits(), currency);
		Transfer transfer = new Transfer(request.sourceAccountId(), request.destinationAccountId(),
				amount, amount, request.requestedAt());
		ReflectionTestUtils.setField(transfer, "id", id);
		return transfer;
	}
}
