package hu.bankmonitor.payments.fx;

import hu.bankmonitor.payments.common.Currency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;

/**
 * The port's HTTP implementation: it turns a provider's vocabulary into this
 * application's, and its bad days into one failure the caller can act on.
 *
 * <p>Spring Framework 7 has no {@code @Recover}, and when the retry budget runs out it
 * rethrows the last attempt's exception unchanged rather than a wrapper announcing
 * exhaustion. So there is nothing to distinguish "the provider returned {@code 503}" from
 * "the provider returned {@code 503} three times" at the point it is caught — which is
 * exactly why the retried call is a bean of its own. Everything reaching the {@code catch}
 * below has already been retried as far as it will be, and the attempt count comes from
 * the same setting the annotation counted with.
 *
 * <p>The reasoning, and the alternatives rejected, are in
 * {@code docs/design-decisions/25-exchange-rate-client.md}.
 */
@Service
class HttpExchangeRateProvider implements ExchangeRateProvider {

	private static final Logger log = LoggerFactory.getLogger(HttpExchangeRateProvider.class);

	private final RetriedQuotes quotes;

	private final ExchangeRateSettings settings;

	private final Clock clock;

	HttpExchangeRateProvider(RetriedQuotes quotes, ExchangeRateSettings settings, Clock clock) {
		this.quotes = quotes;
		this.settings = settings;
		this.clock = clock;
	}

	@Override
	public ExchangeRate exchangeRateFor(Currency base, Currency quote) {
		ProviderQuote answer;
		try {
			answer = quotes.forPair(base, quote);
		}
		catch (HttpServerErrorException | ResourceAccessException exhausted) {
			log.warn("The exchange rate provider did not answer for {}/{} in {} attempts",
					base, quote, settings.attemptsPerRequest(), exhausted);
			throw new ExchangeRateUnavailableException(base, quote, settings.attemptsPerRequest(), exhausted);
		}
		catch (RestClientResponseException refused) {
			throw new IllegalStateException("the exchange rate provider refused a %s/%s request with %s"
					.formatted(base, quote, refused.getStatusCode()), refused);
		}
		catch (RestClientException unreadable) {
			throw new IllegalStateException(
					"the exchange rate provider answered a %s/%s request with something this client could not read"
							.formatted(base, quote), unreadable);
		}
		return asExchangeRate(base, quote, answer);
	}

	/**
	 * Checks the provider's answer against the question before believing it.
	 *
	 * <p>A quote for the wrong pair is the one provider defect that would otherwise be
	 * silent: the number is well-formed, the response is a {@code 200}, and the transfer
	 * settles at a rate for two currencies nobody asked about. So the echo is compared
	 * rather than discarded, and a mismatch is a defect above this client — unchecked, and
	 * nothing catches it — for the same reason ticket 07 refuses a rate of zero.
	 */
	private ExchangeRate asExchangeRate(Currency base, Currency quote, ProviderQuote answer) {
		if (answer == null) {
			throw new IllegalStateException(
					"the exchange rate provider answered a %s/%s request with no body".formatted(base, quote));
		}
		if (!answer.matches(base, quote)) {
			throw new IllegalStateException("asked the exchange rate provider for %s/%s and it quoted %s/%s"
					.formatted(base, quote, answer.base(), answer.quote()));
		}
		return new ExchangeRate(base, quote, answer.rate(), clock.instant());
	}
}
