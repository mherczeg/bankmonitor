package hu.bankmonitor.payments.mockfx;

import hu.bankmonitor.testsupport.ThreadProbeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim that makes the stand-in provider believable: a request to it is served by
 * none of the layers a request to this application is served by.
 *
 * <p>Each test states the difference as something a caller can see, and where there is an
 * application endpoint to compare against it asserts both halves — a header missing from
 * one response proves nothing unless the same request to our own API carries it.
 *
 * <p>The servlet-filter layer design decision 28 names, a logging and MDC filter, is not tested
 * here because this application has none to be excluded from. That gap, and the URL
 * patterns the filter inherits when it arrives, are recorded in {@code docs/deferred.md}.
 */
class MockProviderStaysOutOfOurCrossCuttingLayersTest extends SteadyMockProviderTest {

	private static final String A_PATH_UNDER_THE_PUBLIC_API = ThreadProbeController.PATH;

	private static final String A_PATH_UNDER_THE_MOCK_PROVIDER = ratesUri("EUR", "HUF");

	/** The Vite dev server of design decision 20, the one origin this API's CORS policy names. */
	private static final String FRONTEND_DEV_ORIGIN = "http://localhost:5173";

	/**
	 * Spring Security sets this on everything that passes through the filter chain, so its
	 * absence is the observable difference between the bypass the provider gets and the
	 * {@code permitAll} it deliberately does not. Under {@code permitAll} the request is
	 * still a request the chain decided about, and it would carry the header.
	 */
	private static final String A_SECURITY_RESPONSE_HEADER = "X-Content-Type-Options";

	/**
	 * Qualified by name because Spring MVC contributes a second
	 * {@code CorsConfigurationSource} of its own ({@code mvcHandlerMappingIntrospector});
	 * this one is the bean {@code SecurityConfiguration} declares.
	 */
	@Autowired
	@Qualifier("corsConfigurationSource")
	private CorsConfigurationSource corsPolicySource;

	@Test
	@DisplayName("our own API is served by the security chain")
	void servesThePublicApiThroughTheSecurityChain() {
		client().get().uri(A_PATH_UNDER_THE_PUBLIC_API)
				.exchange()
				.expectHeader().valueEquals(A_SECURITY_RESPONSE_HEADER, "nosniff");
	}

	@Test
	@DisplayName("the provider is bypassed by the security chain, not permitted by it")
	void bypassesTheSecurityChainForTheProvider() {
		client().get().uri(A_PATH_UNDER_THE_MOCK_PROVIDER)
				.exchange()
				.expectStatus().isOk()
				.expectHeader().doesNotExist(A_SECURITY_RESPONSE_HEADER);
	}

	/**
	 * The CORS policy is mapped onto {@code /api/**} alone. A browser has no business
	 * calling a third party's rate service directly, and a policy that let it would be
	 * this application vouching for an origin on that third party's behalf.
	 *
	 * <p><strong>On its own this assertion proves less than it looks like it does.</strong>
	 * CORS is applied here only by the security chain's filter, and the bypass above takes
	 * {@code /mock/**} out of that chain — so this response would carry no
	 * {@code Access-Control-Allow-Origin} even if the policy did name the provider's paths.
	 * It is kept because it is the caller's-eye view of the exclusion, and
	 * {@link #mapsNoCorsPolicyOntoTheProvider()} is what actually holds the mapping to it.
	 */
	@Test
	@DisplayName("a cross-origin preflight to the provider is allowed nothing")
	void allowsNoCrossOriginAccessToTheProvider() {
		client().options().uri(A_PATH_UNDER_THE_MOCK_PROVIDER)
				.header("Origin", FRONTEND_DEV_ORIGIN)
				.header("Access-Control-Request-Method", "GET")
				.exchange()
				.expectHeader().doesNotExist("Access-Control-Allow-Origin");
	}

	/**
	 * The mapping itself, asked what policy it holds for a path under the provider. This
	 * is the half of the CORS exclusion that can fail on its own: registering
	 * {@code /mock/**} on the source would turn this red while every response-header
	 * assertion in this class stayed green.
	 */
	@Test
	@DisplayName("the CORS mapping holds no policy for the provider's paths")
	void mapsNoCorsPolicyOntoTheProvider() {
		assertThat(corsPolicyFor(MockExchangeRateController.RATES_PATH)).isNull();
	}

	/** So that the assertion above is querying a source that maps anything at all. */
	@Test
	@DisplayName("it does hold one for our own API")
	void mapsACorsPolicyOntoThePublicApi() {
		assertThat(corsPolicyFor(A_PATH_UNDER_THE_PUBLIC_API)).isNotNull();
	}

	private CorsConfiguration corsPolicyFor(String path) {
		MockHttpServletRequest preflight = new MockHttpServletRequest(HttpMethod.OPTIONS.name(), path);
		preflight.addHeader("Origin", FRONTEND_DEV_ORIGIN);
		return corsPolicySource.getCorsConfiguration(preflight);
	}

	@Test
	@DisplayName("the same preflight to our own API is answered")
	void allowsCrossOriginAccessToThePublicApi() {
		client().options().uri(A_PATH_UNDER_THE_PUBLIC_API)
				.header("Origin", FRONTEND_DEV_ORIGIN)
				.header("Access-Control-Request-Method", "GET")
				.exchange()
				.expectHeader().valueEquals("Access-Control-Allow-Origin", FRONTEND_DEV_ORIGIN);
	}

	/**
	 * {@code application/problem+json} with a {@code type} URN is this application's error
	 * contract (design decision 18), and a third party emitting it would be the fiction
	 * collapsing in the one place a client actually reads.
	 */
	@Test
	@DisplayName("its refusals are its own, not this application's problem documents")
	void refusesInItsOwnWordsRatherThanOurs() {
		client().get().uri(ratesUri("EUR", "GBP"))
				.exchange()
				.expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
				.expectBody()
				.jsonPath("$.type").doesNotExist()
				.jsonPath("$.title").doesNotExist()
				.jsonPath("$.detail").doesNotExist()
				.jsonPath("$.error").exists();
	}

	/** The same refusal from our own API, so the contrast above is a difference and not a coincidence. */
	@Test
	@DisplayName("the same refusal from our own API is a problem document")
	void refusesOurOwnApiWithAProblemDocument() {
		client().get().uri("/api/no-such-endpoint")
				.exchange()
				.expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.type").exists();
	}

	/**
	 * The published API document is a layer of ours too, and the one whose leak survives
	 * the profile being switched off: the frontend generates its types from a running
	 * backend (design decision 26), so a developer running with this profile and
	 * regenerating would commit a third party's endpoint into {@code schema.gen.ts}.
	 */
	@Test
	@DisplayName("it is absent from the API document the frontend generates from")
	void staysOutOfThePublishedApiDocument() {
		client().get().uri("/v3/api-docs")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.paths['%s']".formatted(MockExchangeRateController.RATES_PATH)).doesNotExist();
	}

	/** So that the assertion above is reading a document that has paths in it at all. */
	@Test
	@DisplayName("our own endpoints are in that document")
	void publishesOurOwnEndpointsInThatDocument() {
		client().get().uri("/v3/api-docs")
				.exchange()
				.expectBody()
				.jsonPath("$.paths['/api/accounts']").exists();
	}
}
