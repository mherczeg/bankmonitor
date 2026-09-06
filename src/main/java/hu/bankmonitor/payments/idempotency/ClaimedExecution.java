package hu.bankmonitor.payments.idempotency;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link IdempotentExecution} over the claim mechanics of {@link IdempotencyClaims}: the
 * three phases of design decision 4, with design decision 5's four answers decided between
 * the first and the last.
 *
 * <p>The order below is the whole of the guarantee, and no two steps are interchangeable:
 *
 * <ol>
 * <li>claim the key, where the unique constraint — not a preceding read — is what
 * discovers a duplicate;
 * <li>if it was refused, read the claim that beat this one and answer from it, without
 * ever reaching the operation;
 * <li>otherwise run the operation and close the claim, in one transaction that commits
 * both or neither.
 * </ol>
 *
 * <p>Phase two of design decision 4 — resolving anything slow with no locks held — has no
 * slot here, because there is nothing to resolve until ticket 26 fetches an Exchange Rate.
 * It is not a hole in the ordering: duplicate resolution already happens above everything,
 * so a duplicate never reaches whatever ends up between the claim and the transaction.
 *
 * <p>Package-private, like everything else in this slice. What another slice may hold is
 * the port.
 */
@Service
class ClaimedExecution implements IdempotentExecution {

	private final IdempotencyClaims claims;

	private final IdempotencyRecordRepository records;

	private final ObjectMapper json;

	/**
	 * The phase-three transaction, opened by hand rather than declared with
	 * {@code @Transactional}, for two reasons that both come from ticket 16.
	 *
	 * <p>An annotation on a method of this class called from another method of this class
	 * is not proxied at all, so the transaction would silently not exist and
	 * {@link IdempotencyClaims#markSucceeded}'s {@code MANDATORY} propagation would be the
	 * only thing to say so. And the release of a failed claim has to happen <em>after</em>
	 * this transaction has ended rather than inside it, which is a line of code with a
	 * template and an invisible property of the method boundary with an annotation.
	 */
	private final TransactionTemplate transaction;

	ClaimedExecution(IdempotencyClaims claims, IdempotencyRecordRepository records, ObjectMapper json,
			PlatformTransactionManager transactionManager) {
		this.claims = claims;
		this.records = records;
		this.json = json;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	@Override
	public <T> T executeOnce(String idempotencyKey, String payloadHash, Class<T> responseType,
			Supplier<T> operation) {
		try {
			claims.claim(idempotencyKey, payloadHash);
		}
		catch (DataIntegrityViolationException duplicate) {
			Optional<T> alreadyAnswered =
					replayOrReclaim(idempotencyKey, payloadHash, responseType, duplicate);
			if (alreadyAnswered.isPresent()) {
				return alreadyAnswered.get();
			}
		}
		return runUnderTheClaim(idempotencyKey, operation);
	}

	/**
	 * Design decision 5's table, read off the claim that already holds the key.
	 *
	 * <p>An empty result means this caller now holds the key and should go on to execute,
	 * which is the {@code FAILED} row and the only one that is neither an answer nor a
	 * refusal. Every other row either returns the first request's response or throws.
	 *
	 * <p><b>An absent row rethrows the violation rather than assuming this was the key's.</b>
	 * It is the guard that keeps the duplicate path from swallowing an insert some other
	 * constraint refused — a payload hash of the wrong length, say — which would otherwise
	 * surface as a {@code 409} naming a claim that does not exist. Matching on the
	 * constraint's name would answer the same question and would tie this class to a string
	 * H2 and PostgreSQL format differently.
	 *
	 * <p>The payload is compared before the status is looked at, which is what makes the
	 * mismatch one row of that table rather than three. A repeat carrying a different
	 * payload is a client mistake whatever the first request got to — and answering it from
	 * a {@code SUCCEEDED} claim would hand a client the response to a request it never made.
	 */
	private <T> Optional<T> replayOrReclaim(String idempotencyKey, String payloadHash,
			Class<T> responseType, DataIntegrityViolationException violation) {
		IdempotencyRecord claimed = records.findByIdempotencyKey(idempotencyKey)
				.orElseThrow(() -> violation);

		if (!claimed.getPayloadHash().equals(payloadHash)) {
			throw new IdempotencyKeyReusedException(idempotencyKey);
		}

		return switch (claimed.getStatus()) {
			case IN_PROGRESS -> throw new RequestInProgressException(idempotencyKey);
			case SUCCEEDED -> Optional.of(json.readValue(claimed.getResponseBody(), responseType));
			case FAILED -> reclaimed(idempotencyKey);
		};
	}

	/**
	 * The loser of a race for a failed key is told the request is in progress, and by then it
	 * is: the winner is holding the claim it just took. Ticket 18 provokes this branch, which
	 * no single-threaded test can reach.
	 */
	private <T> Optional<T> reclaimed(String idempotencyKey) {
		if (!claims.reclaimFailed(idempotencyKey)) {
			throw new RequestInProgressException(idempotencyKey);
		}
		return Optional.empty();
	}

	/**
	 * Phase three: the operation and the flip to {@code SUCCEEDED} in one transaction, and
	 * the release of the claim strictly outside it.
	 *
	 * <p>The {@code catch} sits outside the template deliberately. Releasing a claim commits
	 * on its own so that it survives the rollback it reports, and a new transaction cannot
	 * see the locks the failing one is still holding — so releasing from inside would wait on
	 * a row lock only the transaction it is running within can release. Ticket 16 measured it
	 * at a two-second timeout ending with the row stranded at {@code IN_PROGRESS}, which is
	 * the one state this whole path exists to prevent.
	 *
	 * <p>It catches {@link Throwable} rather than {@link RuntimeException} for that same
	 * reason. An {@link Error} is not this class's to handle and is rethrown untouched, but
	 * letting one past the release would strand the key exactly as the timeout did, and
	 * nothing sweeps stranded keys: a retry of a recoverable failure is the whole promise
	 * this slice makes, and it must not depend on which unchecked throwable arrived.
	 */
	private <T> T runUnderTheClaim(String idempotencyKey, Supplier<T> operation) {
		try {
			return transaction.execute(status -> {
				T answer = operation.get();
				claims.markSucceeded(idempotencyKey, json.writeValueAsString(answer));
				return answer;
			});
		}
		catch (Throwable failure) {
			release(idempotencyKey, failure);
			throw failure;
		}
	}

	/**
	 * A claim that cannot be released is reported attached to the failure that was being
	 * released for, never instead of it: the caller asked about the operation, and a key
	 * stranded by bookkeeping is a second fact rather than a replacement for the first.
	 */
	private void release(String idempotencyKey, Throwable failure) {
		try {
			claims.markFailed(idempotencyKey);
		}
		catch (RuntimeException unrecorded) {
			failure.addSuppressed(unrecorded);
		}
	}
}
