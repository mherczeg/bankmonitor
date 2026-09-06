package hu.bankmonitor.payments.transfers;

/**
 * Raised when a Verdict arrives for a Transfer that has already finished.
 *
 * <p>A Transfer leaves {@code PENDING} once, and everything after that is too late: a Check
 * approving a Transfer another Check rejected, a Check answering one the reaper expired
 * (ticket 23), or a Check service redelivering the very Verdict that settled it. All three
 * are the same fact — the ledger of a finished Transfer no longer decides anything — so they
 * are one refusal rather than three.
 *
 * <p>Carries the status it found as well as the ID, because "too late" is not an answer on
 * its own: a Check service that redelivered a Verdict onto a {@code SETTLED} Transfer has
 * learnt its report landed, and one that finds {@code EXPIRED} has learnt it did not. Ticket
 * 21 chooses the status code and the URN, and this is the field it has to put on the wire
 * for the difference to reach the caller.
 */
public class TransferNotPendingException extends RuntimeException {

	private final long transferId;

	private final TransferStatus status;

	public TransferNotPendingException(long transferId, TransferStatus status) {
		super("transfer %d is already %s".formatted(transferId, status));
		this.transferId = transferId;
		this.status = status;
	}

	public long getTransferId() {
		return transferId;
	}

	/** What the Transfer had already reached, which is the whole of why the Verdict is late. */
	public TransferStatus getStatus() {
		return status;
	}
}
