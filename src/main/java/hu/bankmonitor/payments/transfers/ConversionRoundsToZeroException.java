package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;

import java.math.BigDecimal;

/**
 * Raised when converting a Transfer's amount lands on zero Minor Units of the destination
 * Currency, so there is nothing to credit.
 *
 * <p><b>The arithmetic worked; its answer is that this Transfer cannot be made.</b> No
 * Transfer may debit the source Account and credit the destination nothing, and there is no
 * rounding this way out — the remainder below half a Minor Unit is dropped by design
 * (ticket 07), and a Transfer whose whole amount is that remainder would drop all of it.
 *
 * <p>It is the caller's request that is at fault rather than the provider's rate, which is
 * why this is a {@code 422} and not the {@code 503} an outage gets: one forint into a euro
 * Account is worth less than a cent at any rate anyone will quote, and the operator's
 * remedy is to send more.
 *
 * <p>Everything the refusal compared travels with it — what would have been debited, what
 * it would have arrived as, and the rate — because none of it survives the rollback and a
 * client that wanted to explain the refusal would otherwise have to re-quote to do it.
 */
class ConversionRoundsToZeroException extends RuntimeException {

	private final Money debitedAmount;

	private final Currency destinationCurrency;

	private final BigDecimal exchangeRate;

	ConversionRoundsToZeroException(Money debitedAmount, Currency destinationCurrency, BigDecimal exchangeRate) {
		super("%s converts to no %s at all at %s".formatted(debitedAmount, destinationCurrency, exchangeRate));
		this.debitedAmount = debitedAmount;
		this.destinationCurrency = destinationCurrency;
		this.exchangeRate = exchangeRate;
	}

	/** What would have left the source Account, in the source Account's Currency. */
	Money getDebitedAmount() {
		return debitedAmount;
	}

	/** What the destination Account is denominated in, and what nothing would have arrived in. */
	Currency getDestinationCurrency() {
		return destinationCurrency;
	}

	/** The rate the provider quoted, which is not itself at fault. */
	BigDecimal getExchangeRate() {
		return exchangeRate;
	}
}
