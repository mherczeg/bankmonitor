package hu.bankmonitor.payments;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns the scheduler on, and gives the test suite one switch to turn it off with.
 *
 * <p>It belongs to no slice, so it sits here beside the other three things that do not: the
 * clock, the security chain and the problem document advice. The outbox poller is its first
 * user; design decision 14's expiry reaper is the second, and is cheap partly because this
 * exists.
 *
 * <p><b>Off, a background actor is inert rather than absent.</b> {@code @Scheduled} does
 * nothing at all without {@code @EnableScheduling} — no scheduling infrastructure is
 * registered to read the annotation — so a test that turns this off still gets the poller
 * bean and can drive it by hand, which is what makes "a publish failure leaves the row
 * unsent" an assertion rather than a race against a poller publishing it a moment later. A
 * conditional on the poller bean itself would take that bean away and with it the ability to
 * test the thing at all.
 *
 * <p>A property rather than a profile, on design decision 25's reasoning: Spring caches one
 * application context per distinct configuration, and {@code @TestPropertySource} is how
 * every other test here asks for a variation. The property is also the honest name for what
 * it does — scheduling, not testing — so a deployment that wanted a read-only instance with
 * no background actors has the same switch.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = SchedulingConfiguration.ENABLED, matchIfMissing = true)
class SchedulingConfiguration {

	static final String ENABLED = "payments.scheduling.enabled";

}
