package hu.bankmonitor.payments.idempotency;

import java.util.function.Supplier;

/**
 * Runs an operation at most once per Idempotency Key, and answers every later repeat of
 * that key from what the first attempt left behind.
 *
 * <p>Design decision 3 puts this in the service layer rather than in the request pipeline,
 * and the reason is the race: a filter deciding <em>before</em> the money-moving
 * transaction opens leaves a window between "no prior request found" and "funds reserved",
 * which is precisely what the guarantee exists to close. Behind this seam the claim and the
 * work are two phases of one operation, and the database decides who holds the key.
 *
 * <p>What a caller gets back, given a key that has been seen before:
 *
 * <table>
 *   <caption>Duplicate resolution, before the operation is run</caption>
 *   <tr><th>The key's claim</th><th>What {@code executeOnce} does</th></tr>
 *   <tr><td>held, work unfinished</td><td>throws {@link RequestInProgressException}</td></tr>
 *   <tr><td>held, work finished</td><td>returns the stored response; the operation never runs</td></tr>
 *   <tr><td>released by a failed attempt</td><td>takes the claim back and runs the operation</td></tr>
 *   <tr><td>held for a different payload</td><td>throws {@link IdempotencyKeyReusedException}</td></tr>
 * </table>
 *
 * <p>Every one of those is decided before the operation is reached, so a duplicate costs
 * nothing but the two statements that resolve it.
 *
 * <p><b>The operation runs inside a transaction this port opens</b>, and the claim is
 * closed in that same transaction. A caller's own {@code @Transactional} work therefore
 * joins it rather than committing separately, which is what design decision 4 requires: if
 * the two could commit apart, a crash between them would leave money reserved behind a
 * claim that answers every retry with a refusal.
 *
 * <p><b>An operation that throws releases the claim</b>, so the same key resubmitted is a
 * retry rather than a permanent refusal. That covers a refused request as much as an
 * infrastructure failure — both are reservation-time outcomes, and the guarantee this port
 * makes is about the request rather than about what the Transfer goes on to do.
 */
public interface IdempotentExecution {

	/**
	 * Runs {@code operation} if this key has not produced an answer yet, and returns the
	 * answer it produced when it has.
	 *
	 * <p><b>{@code responseType} is not in design decision 3's sketch of this port, and the
	 * stored response is why it has to be.</b> A replay is read back out of a database
	 * column, so something has to say what to read it back as, and erasure means {@code T}
	 * cannot. The alternative — returning the stored text and leaving the caller to decode
	 * it — moves the same argument to every call site and gives the two paths through this
	 * method two different return types.
	 *
	 * @param idempotencyKey the client's key, unique across every request this service takes
	 * @param payloadHash    what the request said, reduced to the one comparison made
	 *                       against it: a repeat of this key carrying a different hash is a
	 *                       mistake rather than a retry
	 * @param responseType   the type the stored response is read back as, which is the type
	 *                       the operation returns
	 * @param operation      the work to run at most once for this key, in a transaction
	 *                       this port opens
	 * @throws RequestInProgressException   if the key is claimed and its work has not
	 *                                      finished — retryable, and the only one of the two
	 *                                      that is
	 * @throws IdempotencyKeyReusedException if the key was claimed for a different payload
	 */
	<T> T executeOnce(String idempotencyKey, String payloadHash, Class<T> responseType,
			Supplier<T> operation);
}
