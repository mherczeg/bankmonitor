package hu.bankmonitor.payments.mockfx;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The two dials that make the stand-in provider misbehave, and the whole of its
 * configuration.
 *
 * <p>They are the reason it is worth running at all: an FX provider that always answered
 * quickly would leave the timeouts, the retries and the failure mapping around it with
 * nothing to prove. Turning both to zero — as the tests that are about something else do
 * — leaves an ordinary, dull, reliable rate service.
 *
 * <p>The defaults live here rather than in a properties file so there is one copy of
 * them, and so that the settings cannot be half-absent: a missing failure rate would
 * otherwise bind to zero and silently switch the demonstration off.
 *
 * @param failureRate the share of requests answered {@code 503} instead of a rate, from
 *                    {@code 0} for never to {@code 1} for always
 * @param latency     how long every response is held back, the failures included, so that
 *                    a read timeout has a slow path to fire on
 */
@ConfigurationProperties(FlakinessSettings.PREFIX)
@Validated
record FlakinessSettings(
		@DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.3") double failureRate,
		@DefaultValue("200ms") Duration latency) {

	static final String PREFIX = "payments.mock-fx";
}
