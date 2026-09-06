package hu.bankmonitor.payments.mockfx;

import hu.bankmonitor.testsupport.BootedApplicationTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The stand-in provider running with both of its dials turned off, for the tests whose
 * subject is something other than its flakiness.
 *
 * <p>The dials are pinned rather than left at their defaults because those defaults are
 * a coin toss and a sleep: a test that asked for a rate would fail three times in ten and
 * pass the rest, and the suite would learn to distrust itself. Flakiness on purpose gets
 * its own class with its own settings.
 *
 * <p>Holding the annotations here rather than repeating them per class is what lets
 * Spring cache one context across all of them, as {@link BootedApplicationTest} explains.
 */
@ActiveProfiles(MockExchangeRateProvider.PROFILE)
@TestPropertySource(properties = {
		"payments.mock-fx.failure-rate=0",
		"payments.mock-fx.latency=0s"})
abstract class SteadyMockProviderTest extends BootedApplicationTest {

	protected static String ratesUri(String base, String quote) {
		return "%s?base=%s&quote=%s".formatted(MockExchangeRateController.RATES_PATH, base, quote);
	}
}
