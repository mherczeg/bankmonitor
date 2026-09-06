package hu.bankmonitor.payments.idempotency;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Persistence for {@link IdempotencyRecord}, package-private so that reaching it from
 * another slice does not compile (design decision 30).
 *
 * <p>The bare {@link Repository} marker rather than {@code JpaRepository}, on
 * {@link hu.bankmonitor.payments.accounts.Account}'s precedent: every method here has a
 * call site today. {@link #findByIdempotencyKey} is ticket 17's, and is the only read in
 * the interface — everything else decides in the database rather than in memory.
 *
 * <p>Every transition is written as an update guarded on the status it is leaving, rather
 * than by loading the record and setting a field. A read-modify-write would decide the
 * outcome in application memory, where two concurrent retries can both decide they won;
 * these decide it in the database, and the row count says which caller it was.
 */
interface IdempotencyRecordRepository extends Repository<IdempotencyRecord, Long> {

	/** Claims a key, which is how a record comes to exist. The unique constraint is the test. */
	IdempotencyRecord save(IdempotencyRecord record);

	/**
	 * Reads the claim that beat a duplicate to the key, which is the one place the four
	 * answers of design decision 5 can be told apart.
	 *
	 * <p>A read where every other method here is a guarded write, and it is safe to be one:
	 * it runs only after the constraint has already refused an insert, so the row it finds
	 * is somebody else's committed claim rather than a value this caller is about to act on
	 * as though nothing else could change it. What it decides — replay, refuse, or reclaim —
	 * is then re-decided in the database by {@link #reclaimFailed} for the one branch where
	 * two callers can both arrive.
	 *
	 * <p>Empty means the violation came from some other constraint on the row, which is how
	 * {@code ClaimedExecution} tells a duplicate key apart from any other rejected insert
	 * without matching on a constraint name.
	 */
	Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);

	/**
	 * Takes a failed key back to {@code IN_PROGRESS}, and reports whether this caller was
	 * the one that took it.
	 *
	 * <p>The guard on {@code FAILED} is the whole mechanism. Design decision 5 records the
	 * race it closes: two retries of the same failed key both read {@code FAILED} and both
	 * proceed, and the second one moves the money a second time. Here the loser's update
	 * matches nothing and returns {@code 0}.
	 *
	 * @return {@code 1} for the caller that reclaimed the key, {@code 0} for every other
	 */
	@Modifying
	@Query("""
			UPDATE IdempotencyRecord claim
			   SET claim.status = hu.bankmonitor.payments.idempotency.IdempotencyStatus.IN_PROGRESS
			 WHERE claim.idempotencyKey = :idempotencyKey
			   AND claim.status = hu.bankmonitor.payments.idempotency.IdempotencyStatus.FAILED
			""")
	int reclaimFailed(String idempotencyKey);

	/**
	 * Stores the response the claimed work produced and closes the claim.
	 *
	 * <p>Unguarded on status, unlike {@link #reclaimFailed}, because it has no contenders:
	 * the caller holds the claim, and a key can only be held once. The conditional update is
	 * for the transitions several callers race for, not for the ones the winner makes
	 * afterwards.
	 *
	 * <p>The count is still returned, for the other thing a rows-affected number can say:
	 * <em>no row carries this key at all</em>. That is not a race, it is a caller marking
	 * the wrong key — and the row it meant to mark stays where it was. {@link
	 * IdempotencyClaims} turns it into an exception rather than a silent no-op.
	 *
	 * @return {@code 1} when the claim was closed, {@code 0} when no row carries the key
	 */
	@Modifying
	@Query("""
			UPDATE IdempotencyRecord claim
			   SET claim.status = hu.bankmonitor.payments.idempotency.IdempotencyStatus.SUCCEEDED,
			       claim.responseBody = :responseBody
			 WHERE claim.idempotencyKey = :idempotencyKey
			""")
	int markSucceeded(String idempotencyKey, String responseBody);

	/**
	 * Releases the claim for a retry to take. Unguarded on status, and counted, for the same
	 * reasons as {@link #markSucceeded}.
	 *
	 * @return {@code 1} when the claim was released, {@code 0} when no row carries the key
	 */
	@Modifying
	@Query("""
			UPDATE IdempotencyRecord claim
			   SET claim.status = hu.bankmonitor.payments.idempotency.IdempotencyStatus.FAILED
			 WHERE claim.idempotencyKey = :idempotencyKey
			""")
	int markFailed(String idempotencyKey);
}
