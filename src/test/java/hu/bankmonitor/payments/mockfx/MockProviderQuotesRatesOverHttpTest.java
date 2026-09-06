package hu.bankmonitor.payments.mockfx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The provider answering on a real socket, which is the whole reason it exists rather
 * than being a stubbed bean.
 *
 * <p>These tests go through the servlet container for the same reason ticket 25's client
 * will: a rate that arrives over HTTP has passed a URL, a status code and a JSON body,
 * and every one of those is a place the client can be wrong. A double of the Java port
 * would prove none of them.
 */
class MockProviderQuotesRatesOverHttpTest extends SteadyMockProviderTest {

	@Test
	@DisplayName("a supported pair is quoted over HTTP")
	void quotesASupportedPairOverHttp() {
		client().get().uri(ratesUri("EUR", "HUF"))
				.exchange()
				.expectStatus().isOk()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
				.expectBody()
				.jsonPath("$.base").isEqualTo("EUR")
				.jsonPath("$.quote").isEqualTo("HUF")
				.jsonPath("$.rate").isEqualTo(395.0);
	}

	/**
	 * The same pair the other way round, because a wiring that quoted {@code base} against
	 * itself, or swapped the two, would pass the test above on a plausible-looking number.
	 */
	@Test
	@DisplayName("the pair is quoted in the direction it was asked in")
	void quotesThePairInTheDirectionItWasAskedIn() {
		client().get().uri(ratesUri("HUF", "EUR"))
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.base").isEqualTo("HUF")
				.jsonPath("$.quote").isEqualTo("EUR")
				.jsonPath("$.rate").value(Number.class,
						rate -> assertThat(rate.doubleValue()).isCloseTo(1 / 395.0, within(0.000001)));
	}

	/**
	 * The third currency, and the only pair neither side of which is the pivot the table
	 * is written against. It is quoted over HTTP rather than only in {@link QuotedRatesTest}
	 * because "EUR, USD and HUF" is a promise the endpoint makes, and a currency reachable
	 * in the unit test but not on the wire would keep that promise in the wrong place.
	 */
	@Test
	@DisplayName("a cross pair, neither side of which is the pivot, is quoted over HTTP")
	void quotesACrossPairOverHttp() {
		client().get().uri(ratesUri("USD", "HUF"))
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.base").isEqualTo("USD")
				.jsonPath("$.quote").isEqualTo("HUF")
				.jsonPath("$.rate").value(Number.class,
						rate -> assertThat(rate.doubleValue()).isCloseTo(395.0 / 1.08, within(0.000001)));
	}

	/**
	 * A {@code 404} rather than a {@code 400} because the request is well formed and the
	 * rate is simply not something this provider has. The distinction matters downstream:
	 * ticket 25 never retries a {@code 4xx}, and both of these have to be unambiguously
	 * in that class rather than in the retryable one.
	 */
	@Test
	@DisplayName("a currency it does not quote is refused, and not retryably")
	void refusesACurrencyItDoesNotQuote() {
		client().get().uri(ratesUri("EUR", "GBP"))
				.exchange()
				.expectStatus().isNotFound()
				.expectBody()
				.jsonPath("$.error").isEqualTo("unquoted_currency");
	}

	@Test
	@DisplayName("a request that names no currency is refused")
	void refusesARequestThatNamesNoCurrency() {
		client().get().uri(MockExchangeRateController.RATES_PATH)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
				.expectBody()
				.jsonPath("$.error").isEqualTo("missing_parameter");
	}
}
