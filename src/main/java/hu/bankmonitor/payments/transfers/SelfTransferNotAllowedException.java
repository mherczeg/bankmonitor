package hu.bankmonitor.payments.transfers;

/**
 * Raised when both sides of a Transfer name the same Account.
 *
 * <p>Design decision 6 refuses this before any lock is taken, which is why it is thrown
 * ahead of {@link FundsReservation}'s call into the locking operation rather than by that
 * operation: locking one row twice under two names is a question with no good answer, and
 * the cheapest way to never ask it is to refuse the request that would.
 *
 * <p>{@code AccountLocking} keeps a guard of its own underneath, and that is a backstop
 * rather than a duplicate: this one exists to give a client a {@code 422} it can read, and
 * that one exists so a future caller which skipped the refusal fails loudly instead of
 * receiving a pair whose two sides are one row.
 */
class SelfTransferNotAllowedException extends RuntimeException {

	private final long accountId;

	SelfTransferNotAllowedException(long accountId) {
		super("account " + accountId + " cannot be both sides of a transfer");
		this.accountId = accountId;
	}

	/** The Account named on both sides. There is only one, which is the whole problem. */
	long getAccountId() {
		return accountId;
	}
}
