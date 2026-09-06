package hu.bankmonitor.payments.fx;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Where the Exchange Rate provider is, and how long this application will wait for it.
 *
 * <p>Unlike {@code FlakinessSettings}, the defaults are <em>not</em> declared here. Two of
 * these values are also read by {@code @Retryable}, whose attributes are annotation
 * constants resolved as {@code ${…}} placeholders against the {@code Environment} — which
 * has never heard of a {@code @DefaultValue} written on this record. A default declared
 * here would therefore be invisible to the annotation, and a second one written into the
 * placeholder would be the drift the single-copy rule exists to prevent. So every default
 * lives in {@code application.properties}, the one place both readers look, and this
 * record binds without fallbacks: a deployment that deletes a line fails at startup rather
 * than quietly retrying a different number of times than it is documented to.
 *
 * <p>The reasoning, and the alternatives rejected, are in
 * {@code docs/design-decisions/25-exchange-rate-client.md}.
 *
 * @param baseUrl        the provider's root, everything below which is its API and not
 *                       ours — pointing this at a paid provider is the whole substitution
 * @param connectTimeout how long to wait for the provider to accept a connection
 * @param readTimeout    how long to wait for its answer once it has
 * @param maxRetries     retries <em>after</em> the first attempt, so the number of calls a
 *                       failing provider gets is one more than this
 * @param retryDelay     how long to wait before the first retry, doubled for each one
 *                       after it
 */
@ConfigurationProperties(ExchangeRateSettings.PREFIX)
@Validated
record ExchangeRateSettings(
		@NotNull URI baseUrl,
		@NotNull Duration connectTimeout,
		@NotNull Duration readTimeout,
		@Min(0) int maxRetries,
		@NotNull Duration retryDelay) {

	static final String PREFIX = "payments.fx";

	/** The placeholder {@code @Retryable} resolves, not the number — see the class note. */
	static final String MAX_RETRIES_PLACEHOLDER = "${" + PREFIX + ".max-retries}";

	/** The placeholder {@code @Retryable} resolves, not the duration — see the class note. */
	static final String RETRY_DELAY_PLACEHOLDER = "${" + PREFIX + ".retry-delay}";

	/** The number of calls a provider that fails every time will receive. */
	int attemptsPerRequest() {
		return maxRetries + 1;
	}
}
