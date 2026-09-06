package hu.bankmonitor.payments.mockfx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The rates this stand-in provider quotes, and the only part of it that is arithmetic.
 *
 * <p>Currencies are ISO codes as they arrived on the wire, not this application's
 * {@code Currency} enum: a third party has its own idea of what it quotes, and sharing a
 * type with it would be the first thread of the fiction to come loose. A code this table
 * does not hold is quoted at nothing rather than rejected here — what status that becomes
 * is the endpoint's business.
 *
 * <p>Every rate is derived from a single pivot rather than listed pair by pair, so the
 * cross rates cannot disagree with each other. Six hand-written numbers can, and the
 * symptom would show up as an inexplicable remainder in a converted amount.
 */
final class QuotedRates {

	/**
	 * How many units of each currency one Euro buys. Invented figures of roughly the right
	 * magnitude — nothing in this service pretends to know today's market.
	 */
	private static final Map<String, BigDecimal> PER_EURO = Map.of(
			"EUR", BigDecimal.ONE,
			"USD", new BigDecimal("1.08"),
			"HUF", new BigDecimal("395.00"));

	/**
	 * Six decimal places, which is finer than any pair here needs and coarse enough that
	 * the quote reads as a rate rather than as a division that was left running.
	 */
	private static final int QUOTED_SCALE = 6;

	static final Set<String> SUPPORTED_CURRENCIES = PER_EURO.keySet();

	private QuotedRates() {
	}

	/**
	 * The rate to convert one unit of {@code base} into units of {@code quote}.
	 *
	 * @return the rate, or empty if either code is one this provider does not quote
	 */
	static Optional<BigDecimal> between(String base, String quote) {
		BigDecimal euroBuysOfBase = PER_EURO.get(base);
		BigDecimal euroBuysOfQuote = PER_EURO.get(quote);
		if (euroBuysOfBase == null || euroBuysOfQuote == null) {
			return Optional.empty();
		}
		return Optional.of(euroBuysOfQuote.divide(euroBuysOfBase, QUOTED_SCALE, RoundingMode.HALF_UP));
	}
}
