package hu.bankmonitor.payments.fx;

import hu.bankmonitor.payments.common.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A quote from the Exchange Rate provider, and the moment it was given.
 *
 * <p>The pair travels with the number because a rate is meaningless without it: 395 is a
 * fact about EUR against HUF and nonsense about anything else. A caller that holds one of
 * these cannot apply it to the wrong pair without the mismatch being visible in its own
 * code.
 *
 * <p>{@code fetchedAt} is read from this application's {@link java.time.Clock} when the
 * response arrives, not sent by the provider, which quotes no timestamp of its own. It is
 * therefore when <em>we</em> learned the rate rather than when the market set it — the
 * weaker of the two claims, and the only one the wire supports.
 *
 * @param base      the currency one unit of which the rate prices
 * @param quote     the currency the rate is expressed in
 * @param rate      units of {@code quote} per one unit of {@code base}, in major units
 * @param fetchedAt when this application received the quote
 */
public record ExchangeRate(Currency base, Currency quote, BigDecimal rate, Instant fetchedAt) {

	/**
	 * @throws IllegalArgumentException if the rate is not greater than zero, which is a
	 *                                  provider defect rather than an answer — see
	 *                                  {@link CurrencyConversion#convert} for what it would
	 *                                  otherwise be mistaken for
	 */
	public ExchangeRate {
		Objects.requireNonNull(base, "base");
		Objects.requireNonNull(quote, "quote");
		Objects.requireNonNull(rate, "rate");
		Objects.requireNonNull(fetchedAt, "fetchedAt");
		if (rate.signum() <= 0) {
			throw new IllegalArgumentException(
					"%s is not a rate %s can be exchanged at".formatted(rate, base));
		}
	}
}
