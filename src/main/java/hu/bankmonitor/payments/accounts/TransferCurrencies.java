package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Currency;

import java.util.Objects;

/**
 * The Currency each side of a Transfer is denominated in, named by role the way
 * {@link LockedAccounts} names the Accounts themselves.
 *
 * <p>Two Currencies rather than a boolean, because the caller that asks whether they differ
 * is the caller that then has to name the pair to quote. {@link #areTheSame()} is there so
 * that the question every caller actually asks is asked in one place.
 */
public record TransferCurrencies(Currency source, Currency destination) {

	public TransferCurrencies {
		Objects.requireNonNull(source, "a transfer leaves an account denominated in something");
		Objects.requireNonNull(destination, "a transfer arrives at an account denominated in something");
	}

	/** Whether this Transfer needs no conversion, and so no Exchange Rate and no provider. */
	public boolean areTheSame() {
		return source == destination;
	}
}
