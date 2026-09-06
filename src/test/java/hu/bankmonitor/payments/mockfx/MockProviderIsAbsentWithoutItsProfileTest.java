package hu.bankmonitor.payments.mockfx;

import hu.bankmonitor.testsupport.BootedApplicationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a deployment gets: no stand-in provider at all.
 *
 * <p>This runs on the default profiles, which is also what the rest of the suite runs on,
 * so it costs no extra application context — and it is the test that keeps the provider
 * from becoming a permanent fixture of the application by accident.
 */
class MockProviderIsAbsentWithoutItsProfileTest extends BootedApplicationTest {

	@Autowired
	private ApplicationContext context;

	/**
	 * A {@code 403} rather than a {@code 404} is the point of the assertion: the security
	 * bypass is gated on the same profile as the endpoint, so without it {@code /mock/**}
	 * is a path the chain does not name and denies like any other. A {@code 404} here
	 * would mean the bypass had outlived the thing it exists for.
	 */
	@Test
	@DisplayName("its path is denied like any other path the chain does not name")
	void deniesItsPathLikeAnyUnnamedPath() {
		client().get().uri(MockExchangeRateController.RATES_PATH + "?base=EUR&quote=HUF")
				.exchange()
				.expectStatus().isForbidden();
	}

	@Test
	@DisplayName("none of it is in the application context")
	void registersNoneOfItsBeans() {
		assertThat(context.getBeanNamesForType(MockExchangeRateController.class)).isEmpty();
		assertThat(context.getBeanNamesForType(FlakinessSettings.class)).isEmpty();
	}
}
