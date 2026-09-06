package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.ProblemType;
import hu.bankmonitor.testsupport.ScriptedExchangeRates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a request meets when the Exchange Rate provider has nothing to say, and what it is left
 * holding afterwards.
 *
 * <p>This is the one failure the endpoint answers that is nobody's fault on either side of the
 * wire, and the whole of the ticket's answer to it is that <b>failing to the caller is not
 * giving up</b>: the Idempotency Key is released, so the client's retry policy stays "send it
 * again with the same key" rather than "work out whether the first one took effect". The three
 * tests below are the three things that has to mean — an answer a client can act on, nothing
 * left half-written, and a resubmission that actually executes.
 *
 * <p>The outage is scripted with {@link ScriptedExchangeRates#failTheNext}, which counts rather
 * than latches for exactly the third test's sake: the provider has to have come back by the
 * time the same key is resubmitted, or what that test proves is only that a second request is
 * refused twice.
 *
 * <p>{@code ExchangeRateClientGivesUpOnASilentProviderTest} makes the neighbouring claim one
 * layer down — that the client exhausts its retry budget before raising this at all. Here the
 * budget is already spent, and the subject is what the endpoint does with the result.
 */
class AnOutageAtTheProviderLeavesTheKeyRetryableTest extends ScriptedRateScenario {

	private static final long EUR_SOURCE = 1L;

	private static final long HUF_DESTINATION = 2L;

	private static final long BALANCE = 1_000_00L;

	private static final long AMOUNT = 100_50L;

	private static final long CONVERTED = 39_195L;

	private static final String KEY = "0d1f6c1e-6b0a-4a5f-9f1a-2c3d4e5f6a7b";

	@BeforeEach
	void openTwoAccountsInDifferentCurrenciesAndTakeTheProviderDown() {
		rates.quoteAt("390");
		rates.failTheNext(1);
		openAccount(EUR_SOURCE, BALANCE, Currency.EUR);
		openAccount(HUF_DESTINATION, 0L, Currency.HUF);
	}

	/**
	 * {@code 503} rather than {@code 500}, which is the difference between "this service is
	 * broken" and "this service is fine and is waiting on somebody who is not" — and the
	 * {@code Retry-After} is that distinction in the half a client's own code reads.
	 *
	 * <p>The pair and the attempt count are asserted because they are what turns the document
	 * from an apology into something an operator can act on: three attempts already spent says
	 * this was an outage rather than a blip the application would have absorbed on its own.
	 */
	@Test
	@DisplayName("a provider that never answers is a 503 naming the pair, the attempts and when to come back")
	void answersWithTheProvidersUrnAndTheAdviceToComeBack() {
		requestTransfer(KEY, EUR_SOURCE, HUF_DESTINATION, AMOUNT)
				.expectStatus().isEqualTo(503)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "5")
				.expectBody()
				.jsonPath("$.type").isEqualTo(ProblemType.FX_PROVIDER_UNAVAILABLE.urn())
				.jsonPath("$.status").isEqualTo(503)
				.jsonPath("$.detail").isNotEmpty()
				.jsonPath("$.baseCurrency").isEqualTo("EUR")
				.jsonPath("$.quoteCurrency").isEqualTo("HUF")
				.jsonPath("$.attempts").isEqualTo(ScriptedExchangeRates.ATTEMPTS);
	}

	/**
	 * The failure happens in phase two, before the transaction that would write any of this
	 * opens — so there is nothing to roll back, and the assertion is that there was nothing to
	 * roll back rather than that a rollback worked.
	 *
	 * <p>{@code FAILED} rather than {@code IN_PROGRESS} is the load-bearing half. A key stuck
	 * at {@code IN_PROGRESS} would answer the client's retry with the {@code 409} that means
	 * "already running", and the request that the provider's outage refused would be
	 * unrepeatable for as long as that record stood.
	 */
	@Test
	@DisplayName("the refused request writes nothing and leaves its key retryable")
	void leavesNothingBehindAndReleasesTheKey() {
		requestTransfer(KEY, EUR_SOURCE, HUF_DESTINATION, AMOUNT).expectStatus().isEqualTo(503);

		assertThat(transferRows()).isEmpty();
		assertThat(checkLedgerRows()).isEmpty();
		assertThat(reservedAmountOf(EUR_SOURCE)).isZero();
		assertThat(claimedStatus(KEY)).isEqualTo("FAILED");
	}

	/**
	 * What a released key is for, and the reason the two tests above are worth making. The
	 * client resends the identical request under the identical key, the provider answers this
	 * time, and the Transfer is created — one Transfer, at the rate the second quote gave.
	 *
	 * <p>A key released as {@code FAILED} is retaken rather than replayed, which is the
	 * distinction that makes this work: replaying would answer the retry with the stored
	 * {@code 503} forever.
	 */
	@Test
	@DisplayName("the same key resubmitted once the provider is back creates the Transfer")
	void letsTheSameKeyBeResubmittedOnceTheProviderIsBack() {
		requestTransfer(KEY, EUR_SOURCE, HUF_DESTINATION, AMOUNT).expectStatus().isEqualTo(503);

		TransferResponse retried = requestTransfer(KEY, EUR_SOURCE, HUF_DESTINATION, AMOUNT)
				.expectStatus().isCreated()
				.expectBody(TransferResponse.class).returnResult().getResponseBody();

		assertThat(retried.status()).isEqualTo(TransferStatus.PENDING);
		assertThat(retried.creditedAmountMinorUnits()).isEqualTo(CONVERTED);
		assertThat(transferRows()).singleElement()
				.extracting(row -> row.get("ID")).isEqualTo(retried.id());
		assertThat(reservedAmountOf(EUR_SOURCE)).isEqualTo(AMOUNT);
		assertThat(claimedStatus(KEY)).isEqualTo("SUCCEEDED");
	}
}
