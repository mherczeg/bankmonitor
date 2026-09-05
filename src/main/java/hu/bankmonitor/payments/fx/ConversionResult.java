package hu.bankmonitor.payments.fx;

import hu.bankmonitor.payments.common.Money;

/**
 * What converting an amount into another currency produced.
 *
 * <p>Sealed so that the caller's {@code switch} is exhaustive: rounding away to nothing
 * is a refusal, and a shape that let it be mistaken for an amount of zero would let that
 * refusal be forgotten silently. What the refusal becomes on the wire is the caller's,
 * which is why it is not named here.
 */
public sealed interface ConversionResult {

	/** The converted amount, denominated in the destination currency. */
	record Converted(Money amount) implements ConversionResult {
	}

	/**
	 * The amount was worth less than half a Minor Unit of the destination currency, so
	 * there is no amount to credit. Not an error in the conversion — the arithmetic is
	 * correct and its answer is that this transfer cannot be made.
	 */
	record RoundsToZero() implements ConversionResult {
	}
}
