package hu.bankmonitor.payments;

import hu.bankmonitor.payments.common.ProblemType;
import hu.bankmonitor.testsupport.BootedApplicationTest;
import hu.bankmonitor.testsupport.ThreadProbeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter chain of {@link SecurityConfiguration}, asserted through a running server
 * rather than by reading the configuration back.
 *
 * <p>Every test here states an effect a caller can observe — a status code, a response
 * header, the absence of a cookie — because the claim design decision 2 makes is that
 * "no authentication" was configured rather than skipped, and a test that asserted
 * {@code csrf().disable()} was called would only restate the source file.
 */
class SecurityChainTest extends BootedApplicationTest {

	/** The Vite dev server of design decision 20, which runs as its own process. */
	private static final String FRONTEND_DEV_ORIGIN = "http://localhost:5173";

	private static final String A_PATH_UNDER_THE_PUBLIC_API = ThreadProbeController.PATH;

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("the public API answers without credentials")
	void publicApiAnswersWithoutCredentials() {
		client().get().uri(A_PATH_UNDER_THE_PUBLIC_API)
				.exchange()
				.expectStatus().isOk();
	}

	/**
	 * A stateless chain that quietly issued a session cookie would still pass every status
	 * assertion here, so the cookie is what the policy is measured by.
	 */
	@Test
	@DisplayName("no session cookie is issued")
	void issuesNoSessionCookie() {
		client().get().uri(A_PATH_UNDER_THE_PUBLIC_API)
				.exchange()
				.expectHeader().doesNotExist("Set-Cookie");
	}

	/**
	 * With CSRF protection on, {@code CsrfFilter} rejects a token-less POST at
	 * {@code 403} before the request reaches the dispatcher. Reaching the dispatcher — and
	 * being told the path has no POST handler — is therefore the observable difference.
	 */
	@Test
	@DisplayName("an unsafe method needs no CSRF token")
	void unsafeMethodNeedsNoCsrfToken() {
		client().post().uri(A_PATH_UNDER_THE_PUBLIC_API)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
	}

	/**
	 * Left alone, Boot auto-configures an in-memory user with a random password and logs it
	 * at every startup, telling the reader that the security configuration is a placeholder
	 * still to be replaced. There is no login here to hold an account against.
	 */
	@Test
	@DisplayName("no default user account exists")
	void hasNoDefaultUserAccount() {
		assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
	}

	/**
	 * Ticket 21 gave the denial a body. Until then it was an empty {@code 403}, which
	 * answered "you asked for something that is not here" completely; the shared secret
	 * produces the first refusal a client is meant to read, and a chain that wrote a document
	 * for one denial and nothing for the other would be two rules rather than one.
	 */
	@Test
	@DisplayName("a path the chain does not name is denied as a problem document")
	void deniesAPathTheChainDoesNotName() {
		client().get().uri("/not-a-path-the-chain-names")
				.exchange()
				.expectStatus().isForbidden()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.type").isEqualTo(ProblemType.FORBIDDEN.urn())
				.jsonPath("$.instance").isEqualTo("/not-a-path-the-chain-names");
	}

	/**
	 * The authorization filter runs on the {@code ERROR} dispatch too, so a deny-by-default
	 * chain that forgets to permit it turns every 404 and 405 under a permitted prefix into
	 * an empty 403 — and takes ticket 05's problem documents with it.
	 */
	@Test
	@DisplayName("a miss under the public API is a 404, not a denial")
	void missUnderThePublicApiIsNotFoundRatherThanDenied() {
		client().get().uri("/api/no-such-endpoint")
				.exchange()
				.expectStatus().isNotFound();
	}

	@Nested
	@DisplayName("cross-origin")
	class CrossOrigin {

		/**
		 * Security runs before MVC and answers the preflight itself, so this fails unless
		 * the chain carries the CORS entry <em>and</em> a {@code CorsConfigurationSource}
		 * bean exists. A {@code @CrossOrigin} annotation would not be reached.
		 */
		@Test
		@DisplayName("a preflight from the frontend dev origin is answered")
		void answersPreflightFromTheFrontendDevOrigin() {
			client().options().uri(A_PATH_UNDER_THE_PUBLIC_API)
					.header("Origin", FRONTEND_DEV_ORIGIN)
					.header("Access-Control-Request-Method", "GET")
					.exchange()
					.expectStatus().isOk()
					.expectHeader().valueEquals("Access-Control-Allow-Origin", FRONTEND_DEV_ORIGIN);
		}

		@Test
		@DisplayName("the idempotency key survives the preflight")
		void allowsTheIdempotencyKeyHeader() {
			client().options().uri(A_PATH_UNDER_THE_PUBLIC_API)
					.header("Origin", FRONTEND_DEV_ORIGIN)
					.header("Access-Control-Request-Method", "POST")
					.header("Access-Control-Request-Headers", "X-Idempotency-Key")
					.exchange()
					.expectStatus().isOk()
					.expectHeader().valueEquals("Access-Control-Allow-Headers", "X-Idempotency-Key");
		}

		/**
		 * Only the safelisted response headers reach cross-origin JavaScript by default, so
		 * without this the frontend's retry policy reads {@code Retry-After} as absent —
		 * a failure that appears in the browser and not in {@code curl}.
		 */
		@Test
		@DisplayName("Retry-After is readable by cross-origin JavaScript")
		void exposesRetryAfterToTheBrowser() {
			client().get().uri(A_PATH_UNDER_THE_PUBLIC_API)
					.header("Origin", FRONTEND_DEV_ORIGIN)
					.exchange()
					.expectHeader().valueEquals("Access-Control-Expose-Headers", "Retry-After");
		}

		/**
		 * An allow-list that accepted anything would pass every test above.
		 *
		 * <p>It shares the {@code 403} with an authorization denial and is not one:
		 * {@code CorsFilter} writes this refusal itself, ahead of the authorization filter and
		 * of the entry point that turns a denial into a problem document. The body is asserted
		 * for that reason rather than for its own sake — the two refusals arriving in the same
		 * shape would mean a browser could not tell "this origin may not ask" from "this
		 * caller may not have it".
		 */
		@Test
		@DisplayName("a preflight from an unlisted origin is refused, and not as a denial")
		void refusesPreflightFromAnUnlistedOrigin() {
			client().options().uri(A_PATH_UNDER_THE_PUBLIC_API)
					.header("Origin", "https://not-our-frontend.example")
					.header("Access-Control-Request-Method", "GET")
					.exchange()
					.expectStatus().isForbidden()
					.expectHeader().doesNotExist("Access-Control-Allow-Origin")
					.expectBody(String.class)
					.value(body -> assertThat(body).doesNotContain(ProblemType.FORBIDDEN.urn()));
		}

		/**
		 * Credentials off is what keeps CSRF-off safe: no cookie or {@code Authorization}
		 * header rides along on a cross-origin call, so there is no ambient authority for a
		 * forged request to borrow.
		 */
		@Test
		@DisplayName("no credentials ride along on a cross-origin call")
		void allowsNoCredentials() {
			client().options().uri(A_PATH_UNDER_THE_PUBLIC_API)
					.header("Origin", FRONTEND_DEV_ORIGIN)
					.header("Access-Control-Request-Method", "GET")
					.exchange()
					.expectHeader().doesNotExist("Access-Control-Allow-Credentials");
		}
	}
}
