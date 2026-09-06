package hu.bankmonitor.payments.idempotency;

/**
 * How far a claimed Idempotency Key has got, and the whole of what a duplicate request is
 * answered from.
 *
 * <p>{@link #IN_PROGRESS} is the only state a claim is made in; the other two are terminal
 * and mean opposite things to a retry. {@code SUCCEEDED} is final in both directions — the
 * work happened and its response is stored, so a repeat replays it. {@code FAILED} is
 * terminal for <em>this</em> attempt only: design decision 5 makes it explicitly
 * retryable, which is what lets any reservation-time failure be recovered by resubmitting
 * the same key.
 */
enum IdempotencyStatus {

	/** Claimed, and the work it stands for has not finished. A duplicate is told to wait. */
	IN_PROGRESS,

	/** The work happened once. Its response is stored and every later duplicate replays it. */
	SUCCEEDED,

	/** The attempt did not finish. The key may be claimed again — exactly once. */
	FAILED
}
