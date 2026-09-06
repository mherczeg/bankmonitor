package hu.bankmonitor.payments;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;

/**
 * The one real authorization rule in this application: a request to {@code /internal/**}
 * carries the configured secret, or it does not happen.
 *
 * <p>Design decision 10 has why. Verdicts advance a Transfer's lifecycle, so an
 * unauthenticated Verdict endpoint means anyone approves their own Transfer and walks past
 * fraud screening — and unlike the user identity design decision 2 declined to model, this
 * is service-to-service trust across a boundary the asynchronous lifecycle created rather
 * than a question about who the caller is.
 *
 * <p><b>An {@link AuthorizationManager} rather than a filter of our own.</b> The rule then
 * sits on the chain beside the permits it qualifies, where the whole policy can be read in
 * one method, and it runs at the point in the chain that already knows how to translate a
 * refusal into a response. A filter would be a second place authorization happens.
 *
 * <p><b>It grants nothing, and it authenticates nobody.</b> There is no principal here and
 * nothing downstream learns who called: presenting the secret means a request is allowed to
 * proceed, not that this application knows which Check service made it. Telling those apart
 * is what {@code docs/deferred.md} calls for under a real service identity, and this class is
 * deliberately not a step towards one.
 */
class SharedSecretAuthorization implements AuthorizationManager<RequestAuthorizationContext> {

	/**
	 * Not {@code Authorization}, because this is not an HTTP authentication scheme: there is
	 * no challenge to send back and no registered scheme name to send it under. A bespoke
	 * header says what it is, and {@code X-} names the same kind of thing the Idempotency Key
	 * header does at the other end of this API.
	 */
	private static final String SECRET_HEADER = "X-Internal-Secret";

	private final byte[] secret;

	/**
	 * @param secret the configured shared secret, which may not be blank — a blank one would
	 *               make every request with an empty header pass, so the application refuses
	 *               to start rather than starting with the rule silently off
	 */
	SharedSecretAuthorization(String secret) {
		if (!StringUtils.hasText(secret)) {
			throw new IllegalArgumentException(
					"payments.internal.shared-secret must be set: /internal is guarded by it");
		}
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * A missing header and a wrong one are the same answer, so that a caller probing the
	 * endpoint cannot learn which header is the one being checked.
	 *
	 * <p>{@link MessageDigest#isEqual} rather than {@link String#equals}, because it does not
	 * return on the first differing byte. It is not a complete defence — it still returns
	 * early when the lengths differ, so the secret's length is not hidden — and a secret is
	 * chosen long enough that its length is not the interesting part.
	 */
	@Override
	public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication,
			RequestAuthorizationContext context) {
		return new AuthorizationDecision(carriesTheSecret(context.getRequest()));
	}

	private boolean carriesTheSecret(HttpServletRequest request) {
		@Nullable String presented = request.getHeader(SECRET_HEADER);
		return presented != null
				&& MessageDigest.isEqual(secret, presented.getBytes(StandardCharsets.UTF_8));
	}
}
