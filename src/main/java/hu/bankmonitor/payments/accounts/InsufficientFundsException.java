package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Money;

/**
 * Raised when a Transfer asks an Account to reserve more than its Available Balance.
 *
 * <p>Carries both figures because the useful answer to "why was this refused" is the two
 * amounts that were compared, and neither is recoverable from the Account afterwards — by
 * the time a caller reads it the transaction that refused has rolled back. It carries no
 * Account ID, unlike {@link UnknownAccountException}: a Transfer has two Accounts but only
 * one it debits, so there is nothing to disambiguate.
 *
 * <p>Whether the client is told {@code 409} or {@code 422}, and under which problem type
 * URN, belongs to the endpoint in ticket 14.
 */
public class InsufficientFundsException extends RuntimeException {

	private final Money availableBalance;

	private final Money requestedAmount;

	public InsufficientFundsException(Money availableBalance, Money requestedAmount) {
		super("available balance %s does not cover %s".formatted(availableBalance, requestedAmount));
		this.availableBalance = availableBalance;
		this.requestedAmount = requestedAmount;
	}

	/** What the Account could still spend when the reservation was refused. */
	public Money getAvailableBalance() {
		return availableBalance;
	}

	/** What the Transfer asked to reserve. */
	public Money getRequestedAmount() {
		return requestedAmount;
	}
}
