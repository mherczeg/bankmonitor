package hu.bankmonitor.payments.fx;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The single place money changes currency, and the only place in this application where
 * rounding happens.
 *
 * <p>A pure function of its three arguments: no provider, no clock and no Spring, so the
 * most error-prone arithmetic in the backend is testable as a table of worked examples.
 * Fetching the Exchange Rate, locking it onto a Transfer and answering the caller all
 * live above it.
 */
public final class CurrencyConversion {

	private CurrencyConversion() {
	}

	/**
	 * Converts an amount into another currency:
	 * {@code destMinor = round(srcMinor × rate × 10^(destScale − srcScale))}, HALF_EVEN.
	 *
	 * <p>The scale shift is the term that makes 100 HUF and 100 EUR-cents different
	 * quantities. Both currencies count Minor Units, but they count them in different
	 * sizes, so applying the Exchange Rate alone would give an answer in neither.
	 *
	 * <p>Money is not conserved across the two accounts: the remainder below half a Minor
	 * Unit is dropped here and credited nowhere, which is what having no double-entry
	 * ledger costs. See {@code docs/deferred.md}.
	 *
	 * @param exchangeRate the destination currency's major units per one of the source's,
	 *                     which the provider quotes as a decimal — the one value in this
	 *                     domain that genuinely is one, and never itself money
	 * @return the converted amount, or {@link ConversionResult.RoundsToZero} when it
	 *         rounds away to nothing, which is not a Transfer that may be made
	 * @throws IllegalArgumentException if the Exchange Rate is not greater than zero
	 * @throws ArithmeticException if the converted amount does not fit in a {@code long}
	 */
	public static ConversionResult convert(Money source, Currency destination, BigDecimal exchangeRate) {
		if (exchangeRate.signum() <= 0) {
			throw new IllegalArgumentException(
					"%s is not a rate money can be exchanged at".formatted(exchangeRate));
		}

		long destinationMinorUnits = BigDecimal.valueOf(source.minorUnits())
				.multiply(exchangeRate)
				.movePointRight(destination.decimalPlaces() - source.currency().decimalPlaces())
				.setScale(0, RoundingMode.HALF_EVEN)
				.longValueExact();

		return destinationMinorUnits == 0
				? new ConversionResult.RoundsToZero()
				: new ConversionResult.Converted(new Money(destinationMinorUnits, destination));
	}
}
