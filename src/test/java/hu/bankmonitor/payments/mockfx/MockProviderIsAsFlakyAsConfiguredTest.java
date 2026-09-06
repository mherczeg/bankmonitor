package hu.bankmonitor.payments.mockfx;

import hu.bankmonitor.testsupport.BootedApplicationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two dials, turned all the way up: every request fails, and every request is slow.
 *
 * <p>Both extremes are asserted rather than a sampled rate, because a test of "roughly
 * three in ten" is a test that fails on its own schedule. The endpoint's arithmetic is
 * one comparison against a uniform draw, so a rate of one and a rate of zero — pinned by
 * {@link SteadyMockProviderTest} for every other test in this package — between them
 * cover both sides of it.
 */
@ActiveProfiles(MockExchangeRateProvider.PROFILE)
@TestPropertySource(properties = {
		"payments.mock-fx.failure-rate=1",
		"payments.mock-fx.latency=" + MockProviderIsAsFlakyAsConfiguredTest.CONFIGURED_LATENCY})
class MockProviderIsAsFlakyAsConfiguredTest extends BootedApplicationTest {

	private static final long CONFIGURED_LATENCY_MILLIS = 250;

	/** Built from the figure above so the bound property and the assertion cannot drift apart. */
	static final String CONFIGURED_LATENCY = CONFIGURED_LATENCY_MILLIS + "ms";

	private static final Duration EXPECTED_STALL = Duration.ofMillis(CONFIGURED_LATENCY_MILLIS);

	private static final String A_PAIR_IT_WOULD_OTHERWISE_QUOTE =
			MockExchangeRateController.RATES_PATH + "?base=EUR&quote=HUF";

	@Test
	@DisplayName("every request fails when the failure rate is one")
	void failsEveryRequestAtTheTopOfTheRange() {
		client().get().uri(A_PAIR_IT_WOULD_OTHERWISE_QUOTE)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
				.expectBody()
				.jsonPath("$.error").isEqualTo("rate_service_unavailable");
	}

	/**
	 * The failure a client is most likely to see is also the one most likely to be
	 * mistaken for ours, and a {@code 503} carrying a {@code type} URN would be this
	 * application answering for a third party.
	 */
	@Test
	@DisplayName("its failure is not one of this application's problem documents")
	void failsInItsOwnWordsRatherThanOurs() {
		client().get().uri(A_PAIR_IT_WOULD_OTHERWISE_QUOTE)
				.exchange()
				.expectBody()
				.jsonPath("$.type").doesNotExist()
				.jsonPath("$.detail").doesNotExist();
	}

	/**
	 * Measured on a failing response on purpose: a provider that answered its errors
	 * promptly would leave ticket 25's read timeout with nothing to fire on, since the
	 * timeout has to survive the slow path and the failing path being the same path.
	 *
	 * <p>The first request is made and discarded before the clock starts. Without that,
	 * Spring MVC's one-off initialisation on the first call through a mapping is folded
	 * into the measurement, and the assertion passes on warm-up alone — green whether or
	 * not the dial is honoured, which is the failure mode this whole class exists to
	 * avoid. This is a lower bound only: it holds the response to the configured stall,
	 * not to a stall of exactly that length, because an upper bound on a shared machine
	 * measures the machine.
	 */
	@Test
	@DisplayName("a response takes at least the configured latency")
	void respondsNoFasterThanTheConfiguredLatency() {
		client().get().uri(A_PAIR_IT_WOULD_OTHERWISE_QUOTE).exchange();

		long startedAt = System.nanoTime();

		client().get().uri(A_PAIR_IT_WOULD_OTHERWISE_QUOTE).exchange();

		assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
				.isGreaterThanOrEqualTo(EXPECTED_STALL);
	}
}
