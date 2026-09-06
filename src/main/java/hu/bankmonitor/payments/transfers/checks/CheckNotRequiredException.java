package hu.bankmonitor.payments.transfers.checks;

/**
 * Raised when a Verdict names a Check the Transfer's ledger has no row for.
 *
 * <p>The ledger is fixed when the Transfer is written, so a Check outside it is a caller
 * reporting on something nobody asked about — a stale configuration on the reporting side,
 * or the wrong Transfer's identifier. Answering it with a decision would settle or reject a
 * Transfer on the strength of a Check that was never part of its condition.
 *
 * <p>Carries both halves because the useful answer to "why was this refused" names the pair;
 * neither is recoverable from the other. Ticket 21 settled what a client is told: a
 * {@code 404} under a problem type of its own, with both halves on the wire.
 *
 * <p>The constructor is public on {@link hu.bankmonitor.payments.transfers.TransferNotPendingException}'s
 * precedent, and for the same reason: the adapter that puts these fields on the wire lives
 * in another package, and so does the test that pins the document it produces.
 *
 * <p>Unreachable while {@link CheckPolicy} requires both Checks of every Transfer — the only
 * way to a ledger missing one is a row deleted underneath it, which is how the test reaches
 * it. It is still the guard the first conditional policy needs, and a policy is precisely
 * what {@code docs/deferred.md} names as future work.
 */
public class CheckNotRequiredException extends RuntimeException {

	private final long transferId;

	private final Check check;

	public CheckNotRequiredException(long transferId, Check check) {
		super("transfer %d does not require %s".formatted(transferId, check));
		this.transferId = transferId;
		this.check = check;
	}

	public long getTransferId() {
		return transferId;
	}

	public Check getCheck() {
		return check;
	}
}
