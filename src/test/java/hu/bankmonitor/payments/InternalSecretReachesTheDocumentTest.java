package hu.bankmonitor.payments;

import hu.bankmonitor.testsupport.BootedApplicationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That {@code /v3/api-docs} says which operations take the shared secret, and which do not.
 *
 * <p>The internal endpoints are published on purpose — the mock FX provider is hidden because
 * it stands in for somebody else's API, and this one is ours. A Check service being wired up
 * is the reader this document exists for, and the credential it has to send is the one thing
 * about the operation it cannot guess from the shape of the request.
 *
 * <p>The negative half is the load-bearing one. A document-level requirement would describe
 * the whole API as needing a secret the public half does not, which sends an integrator
 * looking for a credential nobody will issue them.
 */
class InternalSecretReachesTheDocumentTest extends BootedApplicationTest {

	private static final String OPENAPI_DOCUMENT = "/v3/api-docs";

	private static final String REPORT_VERDICT =
			"$.paths.['/internal/transfers/{transferId}/checks/{check}'].post";

	private static final String LIST_ACCOUNTS = "$.paths./api/accounts.get";

	@Test
	@DisplayName("the secret is described as a header the caller sends, under the name it is read from")
	void describesTheSecretAsAHeaderApiKey() {
		client().get().uri(OPENAPI_DOCUMENT)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.components.securitySchemes.internalSecret.type").isEqualTo("apiKey")
				.jsonPath("$.components.securitySchemes.internalSecret.in").isEqualTo("header")
				.jsonPath("$.components.securitySchemes.internalSecret.name").isEqualTo("X-Internal-Secret");
	}

	@Test
	@DisplayName("reporting a Verdict is documented as requiring the secret")
	void requiresTheSecretOnTheInternalOperation() {
		client().get().uri(OPENAPI_DOCUMENT)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath(REPORT_VERDICT + ".security")
				.value(List.class, requirements -> assertThat(requirements)
						.singleElement()
						.isEqualTo(Map.of("internalSecret", List.of())));
	}

	/**
	 * The public API is open, and the document has to keep saying so. This is also what a
	 * requirement attached to the whole document rather than to the operations under
	 * {@code /internal} would break, while leaving the test above green.
	 */
	@Test
	@DisplayName("the public API is documented as needing no credential at all")
	void requiresNothingOnAPublicOperation() {
		client().get().uri(OPENAPI_DOCUMENT)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath(LIST_ACCOUNTS + ".security").doesNotExist()
				.jsonPath("$.security").doesNotExist();
	}
}
