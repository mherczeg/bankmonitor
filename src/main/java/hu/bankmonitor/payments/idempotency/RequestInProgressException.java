package hu.bankmonitor.payments.idempotency;

/**
 * Raised when a key is claimed for the same payload and the work behind it has not
 * finished — so the answer this request wants exists nowhere yet.
 *
 * <p>It is the retryable half of the two refusals this slice raises, and the whole reason
 * they are two: the client that meets this one should wait and send the request again, and
 * the client that meets {@link IdempotencyKeyReusedException} must never send it again.
 * Sharing a status code while giving opposite advice is what the distinct type URN and the
 * {@code Retry-After} header exist to undo (design decision 18).
 *
 * <p>How long to wait is the endpoint's to say rather than this slice's, for the same
 * reason the status code is: nothing here knows what the operation behind the key costs.
 */
public class RequestInProgressException extends RuntimeException {

	public RequestInProgressException(String idempotencyKey) {
		super("the request on key " + idempotencyKey + " has not finished");
	}
}
