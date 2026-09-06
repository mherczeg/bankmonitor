package hu.bankmonitor.payments.idempotency;

/**
 * Raised when a key already stands for one request and a different one arrives under it.
 *
 * <p>An Idempotency Key names an intent, so two payloads under one key is a client that
 * reused a key it should have replaced — and no amount of retrying will make the two
 * payloads agree. This is the refusal a client must never repeat, which is why it is a
 * type of its own and why the endpoint answers it with no {@code Retry-After}.
 *
 * <p>The payload behind the claim is deliberately not reported. The record keeps a hash
 * rather than the request precisely so that one caller's payload cannot be read back out
 * through another caller's guess at its key.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

	public IdempotencyKeyReusedException(String idempotencyKey) {
		super("key " + idempotencyKey + " was claimed for a different payload");
	}
}
