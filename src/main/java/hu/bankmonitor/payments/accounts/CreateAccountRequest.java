package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * What opening an Account takes: the currency it is denominated in, and how much it holds
 * to begin with.
 *
 * <p>The amount is a whole count of Minor Units and <b>the field name says so</b>.
 * {@code 10050} in a field called {@code openingBalance} reads as ten thousand and fifty
 * euros to one caller and as a hundred euros fifty to the next; a field called
 * {@code openingBalanceMinorUnits} has one reading. The decimal form an operator types
 * belongs to the form, which converts it before it ever leaves the browser.
 *
 * <p>{@link Currency} rather than a string, so the three denominations this service can
 * quote are a closed set in the generated OpenAPI schema too, and the frontend picks the
 * same list up rather than restating it.
 *
 * <p>The amount is a boxed {@code Long} rather than a primitive {@code long}: a primitive
 * that no JSON member filled would arrive as a perfectly valid zero, so an absent field
 * would open an empty Account instead of being refused, and {@code @NotNull} on a
 * primitive can never fire.
 */
record CreateAccountRequest(

		@NotNull Currency currency,

		@NotNull @PositiveOrZero Long openingBalanceMinorUnits) {

	/**
	 * The two components as the one value the domain has for them — never called before
	 * validation has run, which is what lets it dereference both.
	 */
	Money openingBalance() {
		return new Money(openingBalanceMinorUnits, currency);
	}
}
