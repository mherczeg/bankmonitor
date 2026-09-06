package hu.bankmonitor.payments.idempotency;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The four transitions an {@link IdempotencyRecord} makes, and — the substance of this
 * class — the transaction each one has to make them in.
 *
 * <p>Every method here is one statement and a postcondition, so the propagation is not
 * decoration around the work: it <em>is</em> the work. Three of the four run in a
 * transaction of their own
 * because a claim nobody else can see yet is not a claim, and the fourth deliberately does
 * not, because a success nobody else can see yet is exactly what it should be until the
 * money has moved.
 *
 * <table>
 *   <caption>Why each transition commits when it does</caption>
 *   <tr><th>Transition</th><th>Propagation</th><th>Because</th></tr>
 *   <tr><td>{@link #claim}</td><td>{@code REQUIRES_NEW}</td>
 *       <td>a concurrent duplicate has to hit the constraint, which it cannot do against
 *           an uncommitted row</td></tr>
 *   <tr><td>{@link #reclaimFailed}</td><td>{@code REQUIRES_NEW}</td>
 *       <td>as above, and the row lock must not be held across the work that follows</td></tr>
 *   <tr><td>{@link #markSucceeded}</td><td>{@code MANDATORY}</td>
 *       <td>it has to commit with the money movement it is reporting, or neither</td></tr>
 *   <tr><td>{@link #markFailed}</td><td>{@code REQUIRES_NEW}</td>
 *       <td>it has to survive the rollback it is reporting</td></tr>
 * </table>
 *
 * <p>The class is package-private and the methods are public, which looks backwards and is
 * not: proxy-based AOP silently ignores {@code @Transactional} on a non-public method, so a
 * demotion here would leave four methods that read as transactional and are not, with no
 * error to read (design decision 30).
 */
@Service
class IdempotencyClaims {

	private final IdempotencyRecordRepository records;

	IdempotencyClaims(IdempotencyRecordRepository records) {
		this.records = records;
	}

	/**
	 * Claims a key for a payload, and commits the claim before returning so that a request
	 * arriving a millisecond later finds it there.
	 *
	 * <p>The insert is the test for a duplicate, and the only test. A read first would open
	 * a window between "no prior request found" and the write, which is precisely the race
	 * the whole mechanism exists to close; the unique constraint has no such window because
	 * the database applies it at the moment of the write.
	 *
	 * <p>So the failure is how a duplicate is discovered, and it is left to propagate.
	 * Ticket 17 catches it and reads the row that beat it, which is the only point at which
	 * the difference between "wait and retry" and "you have made a mistake" can be known.
	 *
	 * @throws DataIntegrityViolationException if the key is already claimed
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void claim(String idempotencyKey, String payloadHash) {
		records.save(new IdempotencyRecord(idempotencyKey, payloadHash));
	}

	/**
	 * Takes a key whose last attempt failed, so that exactly one of several concurrent
	 * retries goes on to do the work.
	 *
	 * <p>Committing alone matters twice here. It makes the reclaim visible to the retries
	 * still deciding, as with {@link #claim} — and it puts down the row lock the update
	 * took, which would otherwise be held for as long as the work takes and block every
	 * other retry there rather than turning it away.
	 *
	 * @return whether this caller is the one that reclaimed the key, from the number of
	 *         rows the guarded update matched
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean reclaimFailed(String idempotencyKey) {
		return records.reclaimFailed(idempotencyKey) == 1;
	}

	/**
	 * Closes a claim with the response to replay to every later repeat of the key.
	 *
	 * <p>{@code MANDATORY} rather than {@code REQUIRED}, so that calling this outside the
	 * transaction that did the work fails loudly instead of quietly committing on its own.
	 * Design decision 4 wants this write bundled with the money movement it reports: if the
	 * two could commit separately, a crash between them leaves funds reserved against a
	 * record still reading {@code IN_PROGRESS}, and the client is told to wait forever for
	 * a transfer that already happened. Bundled, the pair either both happened or neither
	 * did, and the surviving crash window — a claim with nothing reserved behind it — is
	 * the one the retry path already handles.
	 *
	 * @throws org.springframework.transaction.IllegalTransactionStateException if there is
	 *         no transaction to join
	 * @throws EmptyResultDataAccessException if no claim is held on the key
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void markSucceeded(String idempotencyKey, String responseBody) {
		requireTheClaimWasThere(records.markSucceeded(idempotencyKey, responseBody),
				"succeeded", idempotencyKey);
	}

	/**
	 * Releases a claim so the key can be retried, in a transaction of its own because the
	 * caller's is on its way to rolling back.
	 *
	 * <p>The one place where {@code REQUIRES_NEW} is not about visibility but about
	 * survival. This reports a failure, so it is called from a failing operation, so
	 * joining that operation's transaction would have the rollback undo the status write —
	 * stranding the row at {@code IN_PROGRESS} and making the key permanently unretryable.
	 * The write has to outlive the thing it describes.
	 *
	 * <p><b>Call it after that transaction has ended, not from inside it.</b> A new
	 * transaction cannot see the locks the suspended one is holding, so reporting the
	 * failure from within a {@code catch} that still sits inside the failing transaction —
	 * which is the obvious place to put it — waits on a row lock only that transaction can
	 * release, and only once this call returns. {@link #markSucceeded} is the one that
	 * belongs inside; this one belongs after.
	 *
	 * <p>It throws when the key was never claimed, which is worth knowing about in the one
	 * place it can happen: this runs while an exception is already on its way up, so a
	 * caller that lets this one replace it has swapped the real failure for a report about
	 * bookkeeping. Report the original and attach this as a suppressed exception.
	 *
	 * @throws EmptyResultDataAccessException if no claim is held on the key
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(String idempotencyKey) {
		requireTheClaimWasThere(records.markFailed(idempotencyKey), "failed", idempotencyKey);
	}

	/**
	 * Neither unguarded update can lose a race, so the only thing a count of zero can mean
	 * is that the key names no row — a caller marking a key it never claimed, while the one
	 * it meant to mark stays {@code IN_PROGRESS} with nothing left that will ever move it.
	 * Silence there is the exact failure this slice exists to prevent, so it is loud.
	 */
	private static void requireTheClaimWasThere(int updated, String transition, String idempotencyKey) {
		if (updated == 0) {
			throw new EmptyResultDataAccessException(
					"no claim on key %s to mark %s".formatted(idempotencyKey, transition), 1);
		}
	}
}
