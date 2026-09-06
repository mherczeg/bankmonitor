package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.common.Currency;

/**
 * Raised when the two Accounts a Transfer names are denominated differently.
 *
 * <p><b>A capability this service does not have yet, not a rule it enforces.</b> The right
 * answer to a Transfer across two Currencies is a conversion, and design decision 15 already
 * says where the Exchange Rate comes from — ticket 26 is where the credited amount stops
 * being a copy of the debited one. This refusal is what stands in until then, and ticket 26
 * deletes it rather than reinterpreting it.
 *
 * <p>It exists because the alternative was worse than a temporary refusal. Both amounts on a
 * Transfer are denominated by the source Account, so without this the request would be
 * accepted and a {@code PENDING} row written recording, say, a hundred euros arriving at a
 * forint Account — right on the source Account's books and wrong on the half nothing yet
 * reads, with nothing to notice it until settlement.
 *
 * <p>Both Currencies travel with it: the client chose two Accounts and neither of them is
 * wrong on its own.
 */
class CrossCurrencyTransferNotSupportedException extends RuntimeException {

	private final Currency sourceCurrency;

	private final Currency destinationCurrency;

	CrossCurrencyTransferNotSupportedException(Currency sourceCurrency, Currency destinationCurrency) {
		super("a transfer from %s to %s needs a conversion this service cannot make yet"
				.formatted(sourceCurrency, destinationCurrency));
		this.sourceCurrency = sourceCurrency;
		this.destinationCurrency = destinationCurrency;
	}

	/** What the source Account is denominated in, and what the Transfer would debit. */
	Currency getSourceCurrency() {
		return sourceCurrency;
	}

	/** What the destination Account is denominated in, and what nothing can credit it in yet. */
	Currency getDestinationCurrency() {
		return destinationCurrency;
	}
}
