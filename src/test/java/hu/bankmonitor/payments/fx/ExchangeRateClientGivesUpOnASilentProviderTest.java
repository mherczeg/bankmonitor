package hu.bankmonitor.payments.fx;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.testsupport.RecordedRetries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The provider fails every request, and the application's own configuration decides how
 * many times it is asked anyway.
 *
 * <p>Nothing is overridden here: {@code max-retries} is whatever
 * {@code application.properties} says, and the assertion below is that the number of
 * attempts follows it. So this is the test that would notice the retry policy being
 * switched off in production while every scripted test in the slice — each of which
 * enables retry itself — stayed green.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
		properties = { "payments.mock-fx.failure-rate=1.0", "payments.mock-fx.latency=0s" })
@ActiveProfiles(SelfHostedProvider.PROFILE)
@Import(RecordedRetries.class)
class ExchangeRateClientGivesUpOnASilentProviderTest {

	@DynamicPropertySource
	static void pointTheClientAtThisApplication(DynamicPropertyRegistry registry) {
		SelfHostedProvider.onItsOwnPort(registry);
	}

	@Autowired
	private ExchangeRateProvider rates;

	@Autowired
	private RecordedRetries retries;

	@Test
	@DisplayName("Three real attempts, then one failure the caller can act on")
	void asksThreeTimesAndThenGivesUp() {
		assertThatExceptionOfType(ExchangeRateUnavailableException.class)
				.isThrownBy(() -> rates.exchangeRateFor(Currency.EUR, Currency.HUF))
				.satisfies(failure -> assertThat(failure.getAttempts()).isEqualTo(3));

		assertThat(retries.failedAttempts()).isEqualTo(3);
		assertThat(retries.gaveUp()).isTrue();
	}
}
