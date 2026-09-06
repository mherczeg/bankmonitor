package hu.bankmonitor.payments.fx;

import hu.bankmonitor.payments.common.Currency;

/**
 * Raised when the Exchange Rate provider did not answer, and had run out of attempts.
 *
 * <p>Distinct from every other way this port can fail, because it is the only one that
 * means <em>try again later</em>. A provider that refuses a request outright — an unquoted
 * currency, a malformed query — has given a deterministic answer, and asking it again
 * produces the same one; those surface as {@link IllegalStateException} and are defects in
 * this application rather than outages at the other end. A caller that could not tell the
 * two apart would invite its own client to retry something that will never succeed.
 *
 * <p>Carries the pair because a failing provider is usually failing for one of them, and
 * the attempt count because "we asked three times" is the difference between a blip this
 * application already absorbed and one it never had a chance against. Which status the
 * refusal becomes, under which problem type URN and with what {@code Retry-After}, belongs
 * to the endpoint in ticket 26.
 *
 * <p>The count is what the configured policy allows, not a tally of what happened: Spring
 * discards the object that knew the real number before this application sees the failure.
 * The two agree whenever the policy is actually running, which is the thing
 * {@code ExchangeRateClientGivesUpOnASilentProviderTest} exists to hold.
 */
public class ExchangeRateUnavailableException extends RuntimeException {

	private final Currency base;

	private final Currency quote;

	private final int attempts;

	public ExchangeRateUnavailableException(Currency base, Currency quote, int attempts, Throwable cause) {
		super("no %s/%s rate after %d attempts".formatted(base, quote, attempts), cause);
		this.base = base;
		this.quote = quote;
		this.attempts = attempts;
	}

	public Currency getBase() {
		return base;
	}

	public Currency getQuote() {
		return quote;
	}

	public int getAttempts() {
		return attempts;
	}
}
