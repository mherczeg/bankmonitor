package hu.bankmonitor.payments.fx;

import hu.bankmonitor.payments.common.Currency;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * One request to the Exchange Rate provider, asked again when the answer was the provider
 * having a bad moment.
 *
 * <p>This is the whole of the retry policy, and it is a separate bean from
 * {@link HttpExchangeRateProvider} for a reason that is structural rather than tidy:
 * {@code @Retryable} is proxy-based, so a call this class made to its own method would
 * bypass the proxy and never retry. The interception has to happen on a boundary between
 * two beans, which puts the mapping of the provider's failures onto ours in the caller.
 *
 * <p>{@code includes} names the two failures worth asking again about — the provider
 * answering {@code 5xx}, and the connection timing out or breaking, which reaches us as
 * {@link ResourceAccessException}. Everything else, {@code 4xx} above all, propagates from
 * the first attempt. A deterministic refusal repeated three times is three refusals and
 * one lie about how hard we tried.
 *
 * <p>The reasoning, and the alternatives rejected, are in
 * {@code docs/design-decisions/25-exchange-rate-client.md}.
 */
@Component
class RetriedQuotes {

	/**
	 * The wire contract of whoever {@code payments.fx.base-url} points at. Deliberately not
	 * shared with the stand-in that also serves this path: that would make the client's URL
	 * a fact about our own test double.
	 */
	private static final String RATES_PATH = "/fx/rates";

	private final RestClient provider;

	RetriedQuotes(RestClient exchangeRateClient) {
		this.provider = exchangeRateClient;
	}

	/**
	 * @throws HttpServerErrorException if the provider failed every attempt
	 * @throws ResourceAccessException if the connection failed or timed out every attempt
	 * @throws org.springframework.web.client.HttpClientErrorException if the provider
	 *         refused the request, on the first attempt and without a second
	 */
	@Retryable(
			includes = { HttpServerErrorException.class, ResourceAccessException.class },
			maxRetriesString = ExchangeRateSettings.MAX_RETRIES_PLACEHOLDER,
			delayString = ExchangeRateSettings.RETRY_DELAY_PLACEHOLDER,
			multiplier = 2.0)
	ProviderQuote forPair(Currency base, Currency quote) {
		return provider.get()
				.uri(builder -> builder.path(RATES_PATH)
						.queryParam("base", base.name())
						.queryParam("quote", quote.name())
						.build())
				.retrieve()
				.body(ProviderQuote.class);
	}
}
