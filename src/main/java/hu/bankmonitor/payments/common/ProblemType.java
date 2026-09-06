package hu.bankmonitor.payments.common;

import java.net.URI;

/**
 * Every problem type this API can answer with, and the sole thing a client branches on.
 *
 * <p>Not the status code, and not a second {@code code} field beside this one: two
 * discriminators drift, and eventually one of them lies. {@link #REQUEST_IN_PROGRESS} and
 * {@link #IDEMPOTENCY_KEY_REUSED} are the case that makes the rule concrete — the same
 * {@code 409}, opposite advice about retrying.
 *
 * <p>The vocabulary lives here, in the package every slice depends on, so that the
 * backend and the frontend's generated types agree from one place. A slice that starts
 * refusing something new adds its constant here rather than writing a URN at the throw
 * site.
 */
public enum ProblemType {

	/** The request was readable, and what it said was not acceptable. Carries per-field detail. */
	VALIDATION_FAILED("validation-failed"),

	/** The request body could not be read at all — malformed JSON. */
	MALFORMED_REQUEST("malformed-request"),

	UNSUPPORTED_MEDIA_TYPE("unsupported-media-type"),

	METHOD_NOT_ALLOWED("method-not-allowed"),

	NOT_FOUND("not-found"),

	/** Both sides of a Transfer named the same Account. */
	SELF_TRANSFER("self-transfer"),

	/** A Transfer named an Account that does not exist. Carries the ID that was wrong. */
	UNKNOWN_ACCOUNT("unknown-account"),

	/** The source Account's Available Balance does not cover the Transfer. Carries both figures. */
	INSUFFICIENT_FUNDS("insufficient-funds"),

	/**
	 * The two Accounts are denominated differently, which this service cannot convert
	 * between yet. Names a capability rather than a rule, because ticket 26 deletes it
	 * rather than reinterpreting it.
	 */
	CROSS_CURRENCY_UNSUPPORTED("cross-currency-unsupported"),

	/** The same idempotency key is being processed right now. Retryable, and says when. */
	REQUEST_IN_PROGRESS("request-in-progress"),

	/** The same idempotency key was used for a different payload. Retrying can never succeed. */
	IDEMPOTENCY_KEY_REUSED("idempotency-key-reused"),

	/** The exchange rate provider failed every attempt. Retryable, and says when. */
	FX_PROVIDER_UNAVAILABLE("fx-provider-unavailable"),

	/**
	 * The caller's fault, and this API does not name it more precisely — a {@code 406},
	 * say. The honest answer for a client error nothing above describes: a URN that
	 * claimed more than is known would be the discriminator lying, and a client shows an
	 * unrecognised type generically anyway.
	 */
	CLIENT_ERROR("client-error"),

	/** Something broke on this side. Deliberately says nothing else. */
	INTERNAL_ERROR("internal-error");

	private static final String NAMESPACE = "urn:problem:";

	private final URI uri;

	ProblemType(String name) {
		this.uri = URI.create(NAMESPACE + name);
	}

	/** The value of the problem document's {@code type} member. */
	public URI uri() {
		return uri;
	}

	/** The same value as text, for comparing against a document that has been parsed. */
	public String urn() {
		return uri.toString();
	}
}
