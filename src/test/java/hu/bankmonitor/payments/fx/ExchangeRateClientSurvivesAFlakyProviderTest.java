package hu.bankmonitor.payments.fx;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.testsupport.RecordedRetries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The resilience policy of design decision 27, exercised one scripted provider at a time.
 *
 * <p>The double sits on the transport rather than on {@link ExchangeRateProvider}: a stub
 * implementing the interface would put the failure simulation <em>above</em> the HTTP
 * client, so the timeouts, the retry policy and the error mapping being demonstrated here
 * would never run and the test would be a test of the stub.
 *
 * <p>Its one limitation is named in {@code docs/deferred.md}:
 * {@link MockRestServiceServer} replaces the request factory it binds to, taking the real
 * connect and read timeouts with it. So {@link #retriesAConnectionThatWentQuiet} proves
 * the policy treats a timeout as worth retrying, and cannot prove the client gives up at
 * two seconds rather than at the provider's convenience.
 */
@SpringBootTest(
		classes = ExchangeRateClientSurvivesAFlakyProviderTest.ScriptedProvider.class,
		properties = { "payments.fx.max-retries=2", "payments.fx.retry-delay=1ms" })
class ExchangeRateClientSurvivesAFlakyProviderTest {

	private static final String RATES = ScriptedProvider.BASE_URL + "/fx/rates?base=EUR&quote=HUF";

	private static final String QUOTED = """
			{"base":"EUR","quote":"HUF","rate":395.12}""";

	@Autowired
	private ExchangeRateProvider rates;

	@Autowired
	private MockRestServiceServer provider;

	@Autowired
	private RecordedRetries retries;

	@BeforeEach
	void forgetTheLastScript() {
		provider.reset();
		retries.forget();
	}

	/**
	 * The ticket's scenario, and the reason the retry event is asserted alongside the
	 * result: a client that simply swallowed the two failures and returned a stale or
	 * invented rate would satisfy the first assertion on its own.
	 */
	@Test
	@DisplayName("Two failures then an answer is an answer, and the retries are on the record")
	void recoversFromTwoFailedAttempts() {
		provider.expect(times(2), requestTo(RATES)).andExpect(method(HttpMethod.GET))
				.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
		provider.expect(once(), requestTo(RATES)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(QUOTED, MediaType.APPLICATION_JSON));

		ExchangeRate rate = rates.exchangeRateFor(Currency.EUR, Currency.HUF);

		assertThat(rate.rate()).isEqualByComparingTo("395.12");
		assertThat(rate.base()).isEqualTo(Currency.EUR);
		assertThat(rate.quote()).isEqualTo(Currency.HUF);
		assertThat(rate.fetchedAt()).isEqualTo(ScriptedProvider.FETCHED_AT);
		assertThat(retries.failedAttempts()).isEqualTo(2);
		assertThat(retries.gaveUp()).isFalse();
		provider.verify();
	}

	/**
	 * Three attempts because {@code maxRetries} counts the retries and not the calls. A
	 * fourth request would fail this test at the mock server rather than at the assertion,
	 * which is the stricter of the two failures.
	 */
	@Test
	@DisplayName("A provider that never answers becomes one failure naming what was tried")
	void exhaustedRetriesBecomeADistinctFailure() {
		provider.expect(times(3), requestTo(RATES)).andExpect(method(HttpMethod.GET))
				.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

		assertThatExceptionOfType(ExchangeRateUnavailableException.class)
				.isThrownBy(() -> rates.exchangeRateFor(Currency.EUR, Currency.HUF))
				.satisfies(failure -> {
					assertThat(failure.getAttempts()).isEqualTo(3);
					assertThat(failure.getBase()).isEqualTo(Currency.EUR);
					assertThat(failure.getQuote()).isEqualTo(Currency.HUF);
				});

		assertThat(retries.gaveUp()).isTrue();
		provider.verify();
	}

	/**
	 * The mock server is scripted with a single expectation, so a second request fails the
	 * test with "no further requests expected" — the assertion that no retry happened is
	 * the script itself, not a count read afterwards.
	 *
	 * <p>It is not an {@link ExchangeRateUnavailableException} because that exception means
	 * <em>try again later</em>, and this provider has just said the answer will not change.
	 */
	@Test
	@DisplayName("A refusal is never asked twice, and is not the failure that invites a retry")
	void neverRetriesARefusal() {
		provider.expect(once(), requestTo(RATES)).andExpect(method(HttpMethod.GET))
				.andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThatThrownBy(() -> rates.exchangeRateFor(Currency.EUR, Currency.HUF))
				.isInstanceOf(IllegalStateException.class)
				.isNotInstanceOf(ExchangeRateUnavailableException.class);

		provider.verify();
	}

	/**
	 * A connection that accepts the request and then says nothing reaches the client as a
	 * {@code ResourceAccessException}, not as a status — a different branch of the retry
	 * policy from the one the {@code 503}s exercise, and the one an unreliable provider
	 * actually produces most often.
	 */
	@Test
	@DisplayName("A connection that went quiet is retried like a 503")
	void retriesAConnectionThatWentQuiet() {
		provider.expect(times(2), requestTo(RATES))
				.andRespond(withException(new HttpTimeoutException("the provider went quiet")));
		provider.expect(once(), requestTo(RATES))
				.andRespond(withSuccess(QUOTED, MediaType.APPLICATION_JSON));

		ExchangeRate rate = rates.exchangeRateFor(Currency.EUR, Currency.HUF);

		assertThat(rate.rate()).isEqualByComparingTo("395.12");
		assertThat(retries.failedAttempts()).isEqualTo(2);
		provider.verify();
	}

	/**
	 * A well-formed {@code 200} for the wrong pair is the one provider defect that would
	 * otherwise settle a Transfer at a rate nobody asked for. Not retryable — asking again
	 * gets the same wrong answer — and not the caller's to handle.
	 */
	@Test
	@DisplayName("A quote for a pair nobody asked about is refused rather than believed")
	void refusesAQuoteForTheWrongPair() {
		provider.expect(once(), requestTo(RATES)).andRespond(
				withSuccess("""
						{"base":"USD","quote":"HUF","rate":361.40}""", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> rates.exchangeRateFor(Currency.EUR, Currency.HUF))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("USD");

		provider.verify();
	}

	/**
	 * A {@code 200} carrying something that is not a quote is a different failure from a
	 * refusal, and saying "the provider refused" would misdescribe it: nothing was refused,
	 * the answer simply could not be read. Not retried, because a provider talking a
	 * language this client does not parse will still be talking it on the second attempt.
	 */
	@Test
	@DisplayName("A body this client cannot read is reported as that, not as a refusal")
	void doesNotCallAnUnreadableAnswerARefusal() {
		provider.expect(once(), requestTo(RATES))
				.andRespond(withSuccess("{\"base\":\"EUR\",", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> rates.exchangeRateFor(Currency.EUR, Currency.HUF))
				.isInstanceOf(IllegalStateException.class)
				.isNotInstanceOf(ExchangeRateUnavailableException.class)
				.hasMessageContaining("could not read")
				.hasMessageNotContaining("refused");

		provider.verify();
	}

	/**
	 * The other half of the split: a status the provider chose to answer with is a refusal,
	 * and the status belongs in the message because it is the one thing that says which.
	 */
	@Test
	@DisplayName("A refusal names the status the provider refused with")
	void namesTheStatusARefusalCameWith() {
		provider.expect(once(), requestTo(RATES)).andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThatThrownBy(() -> rates.exchangeRateFor(Currency.EUR, Currency.HUF))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("refused")
				.hasMessageContaining("404");

		provider.verify();
	}

	/**
	 * Everything the port needs and nothing the application does not: the retry
	 * post-processor, the two beans it sits between, and a {@link RestClient} pointed at a
	 * server that only exists inside this test.
	 */
	@Configuration
	@EnableResilientMethods
	@EnableConfigurationProperties(ExchangeRateSettings.class)
	@Import({ RetriedQuotes.class, HttpExchangeRateProvider.class, RecordedRetries.class })
	static class ScriptedProvider {

		static final String BASE_URL = "http://exchange-rates.test";

		static final Instant FETCHED_AT = Instant.parse("2026-01-01T09:30:00Z");

		@Bean
		RestClient.Builder exchangeRateClientBuilder() {
			return RestClient.builder().baseUrl(BASE_URL);
		}

		@Bean
		MockRestServiceServer provider(RestClient.Builder builder) {
			return MockRestServiceServer.bindTo(builder).build();
		}

		/**
		 * The unused parameter is load-bearing: binding the mock server swaps the builder's
		 * request factory, so it has to happen before the client is built. Dropping it
		 * leaves this test talking to a hostname that does not resolve.
		 */
		@Bean
		RestClient exchangeRateClient(RestClient.Builder builder, MockRestServiceServer boundFirst) {
			return builder.build();
		}

		@Bean
		Clock clock() {
			return Clock.fixed(FETCHED_AT, ZoneOffset.UTC);
		}
	}
}
