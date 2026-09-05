package hu.bankmonitor.payments.common;

/**
 * The three denominations an Account's balance may be held in.
 *
 * <p>Deliberately not {@link java.util.Currency}, which is open to every ISO 4217 code
 * there is. This context supports exactly three, so the type that names them should be
 * closed: a currency this service cannot price is then not a value anything can hold,
 * rather than a value some validator has to remember to reject.
 *
 * <p>Each constant carries the number of decimal places it is conventionally written
 * with, and {@link #decimalPlaces()} is the only place that fact lives. <b>It is read at
 * the edges and nowhere else</b> — the form that parses an operator's {@code "100.50"}
 * into Minor Units, the display that turns Minor Units back into it, and the
 * cross-currency conversion, which shifts by the difference between two scales. The core
 * counts Minor Units and never divides by a hundred.
 */
public enum Currency {

	EUR(2),
	USD(2),

	/** The reason the scale shift in a conversion cannot be dropped: fillér are not counted in hundredths. */
	HUF(0);

	private final int decimalPlaces;

	Currency(int decimalPlaces) {
		this.decimalPlaces = decimalPlaces;
	}

	/** How many decimal places an amount in this currency is written with: 2 for EUR and USD, 0 for HUF. */
	public int decimalPlaces() {
		return decimalPlaces;
	}
}
