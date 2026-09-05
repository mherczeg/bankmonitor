package hu.bankmonitor.payments.accounts;

import java.util.Objects;

/**
 * The two Accounts a Transfer touches, named by their role in it and locked for the rest
 * of the transaction that asked for them.
 *
 * <p>The order they were locked in is deliberately not recoverable from here. It is a
 * property of the acquisition (design decision 6) and no business of the caller, which
 * reads the source to check its Available Balance and credits the destination regardless
 * of which of the two the database happened to see first.
 *
 * <p>Valid only inside that transaction: once it ends the locks are released and these
 * become detached entities whose balances anyone may already have changed.
 */
public record LockedAccounts(Account source, Account destination) {

	public LockedAccounts {
		Objects.requireNonNull(source, "a transfer has a source account");
		Objects.requireNonNull(destination, "a transfer has a destination account");
	}
}
