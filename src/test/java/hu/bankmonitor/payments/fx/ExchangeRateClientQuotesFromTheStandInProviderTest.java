package hu.bankmonitor.payments.fx;

import hu.bankmonitor.payments.common.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The port, its client, its settings and a provider on a real socket, wired the way the
 * application wires them.
 *
 * <p>Everything else in this slice's suite replaces the transport in order to script it,
 * which means nothing else proves that the configured base URL, the path the client
 * appends to it, the JSON the provider actually sends and the {@code Currency} it is
 * mapped onto agree with each other. A typo in any one of them passes every scripted test
 * and fails here.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
		properties = { "payments.mock-fx.failure-rate=0", "payments.mock-fx.latency=0s" })
@ActiveProfiles(SelfHostedProvider.PROFILE)
class ExchangeRateClientQuotesFromTheStandInProviderTest {

	@DynamicPropertySource
	static void pointTheClientAtThisApplication(DynamicPropertyRegistry registry) {
		SelfHostedProvider.onItsOwnPort(registry);
	}

	@Autowired
	private ExchangeRateProvider rates;

	/**
	 * The rate is asserted as a range rather than a figure. What the stand-in quotes is its
	 * business and ticket 24 already pins it; what this test owns is that a number arrived
	 * for the pair that was asked about.
	 */
	@Test
	@DisplayName("A rate comes back over real HTTP for the pair that was asked for")
	void quotesAPairOverHttp() {
		ExchangeRate rate = rates.exchangeRateFor(Currency.EUR, Currency.HUF);

		assertThat(rate.base()).isEqualTo(Currency.EUR);
		assertThat(rate.quote()).isEqualTo(Currency.HUF);
		assertThat(rate.rate()).isGreaterThan(BigDecimal.ZERO);
		assertThat(rate.fetchedAt()).isNotNull();
	}

	@Test
	@DisplayName("The pair is read in both directions, so neither is the query's fixed order")
	void quotesTheSamePairBackwards() {
		ExchangeRate forwards = rates.exchangeRateFor(Currency.EUR, Currency.HUF);
		ExchangeRate backwards = rates.exchangeRateFor(Currency.HUF, Currency.EUR);

		assertThat(backwards.base()).isEqualTo(Currency.HUF);
		assertThat(backwards.rate()).isLessThan(forwards.rate());
	}
}
