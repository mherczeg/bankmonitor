package hu.bankmonitor.payments.transfers;

/**
 * Raised when an identifier addresses a Transfer the table has no row for.
 *
 * <p>Unlike {@link hu.bankmonitor.payments.accounts.UnknownAccountException}, this one is
 * about a <em>path</em>: the identifier is the resource's own address rather than a value
 * inside a payload, so the answer it earns is the {@code 404} ticket 05 settled — <em>the
 * path names nothing</em> — and not the {@code 422} ticket 14 gives an unknown Account.
 * Which status that is, and under which problem type URN, still belongs to the controller.
 *
 * <p>It carries the identifier so the answer can name what was looked for, on
 * {@code UnknownAccountException}'s precedent.
 *
 * <p>{@link VerdictRecording} raises the same exception for a Verdict naming no Transfer, and
 * decides existence under the row lock rather than checking for it beforehand — for the same
 * reason an Account's is decided there: a read taken outside the transaction could be true
 * when it was taken and false by the time the Verdict is written. What status that earns is
 * still the adapter's, and ticket 21's endpoint need not answer it as this one's {@code 404}.
 */
class UnknownTransferException extends RuntimeException {

	private final long transferId;

	UnknownTransferException(long transferId) {
		super("no transfer with ID " + transferId);
		this.transferId = transferId;
	}

	long getTransferId() {
		return transferId;
	}
}
