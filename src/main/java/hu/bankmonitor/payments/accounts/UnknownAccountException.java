package hu.bankmonitor.payments.accounts;

/**
 * Raised when a Transfer names an Account ID the table has no row for.
 *
 * <p>Carries the ID because the caller has two of them and the answer it owes the client
 * has to say which one was wrong. Whether that answer is a {@code 404} or a {@code 422},
 * and under which problem type URN, belongs to the endpoint in ticket 14.
 *
 * <p>Existence is decided under the lock rather than checked before it: a read taken
 * outside the transaction could be true when it was taken and false by the time the
 * reservation is written.
 */
public class UnknownAccountException extends RuntimeException {

	private final long accountId;

	public UnknownAccountException(long accountId) {
		super("no account with ID " + accountId);
		this.accountId = accountId;
	}

	public long getAccountId() {
		return accountId;
	}
}
