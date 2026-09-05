package hu.bankmonitor.payments;

import hu.bankmonitor.payments.common.ProblemType;
import hu.bankmonitor.testsupport.ProblemProbeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

/**
 * The one error format, asserted at the web layer with no database behind it.
 *
 * <p>Every test states the {@code type} URN, because that URN is the only field a client
 * is allowed to branch on (design decision 18). The shape assertions are here for the
 * same reason: a caller that has parsed one problem document has parsed all of them, so
 * a failure the framework raises before any of our code runs has to be
 * indistinguishable, in shape, from one a slice raises deliberately.
 *
 * <p>{@code @WebMvcTest} rather than a booted application: nothing here touches
 * persistence, and the contract belongs to the dispatcher and the exception handler in
 * front of it.
 *
 * <p>It names its controller, which looks redundant beside the {@code @Import} and is not.
 * A bare {@code @WebMvcTest} component-scans every controller in the application, and from
 * ticket 10 that means a real one, whose service and repository the web slice deliberately
 * does not provide — the context then fails to start on a missing {@code AccountService}.
 * Naming {@link ProblemProbeController} narrows the scan to a class that lives outside the
 * application's packages, so it matches nothing; the {@code @Import} is still what registers
 * the probe.
 */
@WebMvcTest(ProblemProbeController.class)
@Import({SecurityConfiguration.class, ProblemProbeController.class})
class ProblemDocumentContractTest {

	@Autowired
	private MockMvcTester mvc;

	@Test
	@DisplayName("a validation failure reports one entry per rejected field")
	void validationFailureReportsOneEntryPerRejectedField() {
		MvcTestResult result = mvc.post().uri(ProblemProbeController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"reference": "", "weight": -1}""")
				.exchange();

		assertThat(result).hasStatus(400);
		assertThatIsAProblemDocument(result, ProblemType.VALIDATION_FAILED);
		assertThat(result).bodyJson()
				.extractingPath("$.errors[*].field").asInstanceOf(LIST)
				.containsExactly("reference", "weight");
		assertThat(result).bodyJson()
				.extractingPath("$.errors[?(@.field == 'reference')].message")
				.asInstanceOf(LIST)
				.isNotEmpty();
	}

	/**
	 * A constraint on a query parameter, a path variable or a header is a different
	 * exception inside Spring. If only the body case carried the extension member, the
	 * document's shape would depend on where the rejected value arrived — a distinction
	 * the client cannot see and cannot branch on.
	 */
	@Test
	@DisplayName("a rejected parameter reports its field too, not only a rejected body")
	void rejectedParameterReportsItsFieldToo() {
		MvcTestResult result = mvc.get()
				.uri(ProblemProbeController.PATH + ProblemProbeController.CONSTRAINED_PARAMETER)
				.param("pageSize", "-1")
				.exchange();

		assertThat(result).hasStatus(400);
		assertThatIsAProblemDocument(result, ProblemType.VALIDATION_FAILED);
		assertThat(result).bodyJson()
				.extractingPath("$.errors[*].field").asInstanceOf(LIST)
				.containsExactly("pageSize");
	}

	/**
	 * The default packs every violation into one sentence in {@code detail}. A form that
	 * has to mark up the offending input cannot read that, which is the whole reason for
	 * the extension member above — so the member has to be there even when a single field
	 * is wrong.
	 */
	@Test
	@DisplayName("a single violation is still reported as a field entry")
	void singleViolationIsStillReportedAsAFieldEntry() {
		MvcTestResult result = mvc.post().uri(ProblemProbeController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"reference": "ok", "weight": -1}""")
				.exchange();

		assertThat(result).bodyJson()
				.extractingPath("$.errors[*].field").asInstanceOf(LIST)
				.containsExactly("weight");
	}

	@Test
	@DisplayName("malformed JSON is a problem document")
	void malformedJsonIsAProblemDocument() {
		MvcTestResult result = mvc.post().uri(ProblemProbeController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ not json")
				.exchange();

		assertThat(result).hasStatus(400);
		assertThatIsAProblemDocument(result, ProblemType.MALFORMED_REQUEST);
	}

	/**
	 * The line between the two: JSON that does not parse is unreadable, while JSON that
	 * parses into a value the field will not take is a rejected field, and a client told
	 * the second is the first goes looking for a missing brace. The deserializer stops at
	 * the first such value, so this document names one field where a constraint failure
	 * would name every one.
	 */
	@Test
	@DisplayName("a value the deserializer will not take is a rejected field, not a malformed body")
	void rejectedValueIsReportedAgainstItsField() {
		MvcTestResult result = mvc.post().uri(ProblemProbeController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"reference": "ok", "weight": "heavy"}""")
				.exchange();

		assertThat(result).hasStatus(400);
		assertThatIsAProblemDocument(result, ProblemType.VALIDATION_FAILED);
		assertThat(result).bodyJson()
				.extractingPath("$.errors[*].field").asInstanceOf(LIST)
				.containsExactly("weight");
	}

	@Test
	@DisplayName("an unsupported media type is a problem document")
	void unsupportedMediaTypeIsAProblemDocument() {
		MvcTestResult result = mvc.post().uri(ProblemProbeController.PATH)
				.contentType(MediaType.TEXT_PLAIN)
				.content("reference=ok")
				.exchange();

		assertThat(result).hasStatus(415);
		assertThatIsAProblemDocument(result, ProblemType.UNSUPPORTED_MEDIA_TYPE);
	}

	@Test
	@DisplayName("a method the endpoint does not have is a problem document")
	void wrongMethodIsAProblemDocument() {
		MvcTestResult result = mvc.put().uri(ProblemProbeController.PATH).exchange();

		assertThat(result).hasStatus(405);
		assertThatIsAProblemDocument(result, ProblemType.METHOD_NOT_ALLOWED);
	}

	/**
	 * The framework calls this one "No static resource …", naming the handler that ran
	 * out of options. This API serves no static resources, and a caller told about them
	 * has been handed a detail it cannot act on.
	 */
	@Test
	@DisplayName("a path that does not exist is a problem document about an endpoint")
	void unknownPathIsAProblemDocument() {
		MvcTestResult result = mvc.get().uri("/api/no-such-endpoint").exchange();

		assertThat(result).hasStatus(404);
		assertThatIsAProblemDocument(result, ProblemType.NOT_FOUND);
		assertThat(result).bodyJson().extractingPath("$.detail").asString().doesNotContain("static resource");
		assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/api/no-such-endpoint");
	}

	/**
	 * An unhandled exception is the one case where the framework's own fallback is not a
	 * problem document at all, and where the temptation is to echo the exception message
	 * to the caller.
	 */
	@Test
	@DisplayName("a failure inside a handler is a problem document that leaks nothing")
	void handlerFailureIsAProblemDocumentThatLeaksNothing() {
		MvcTestResult result = mvc.get()
				.uri(ProblemProbeController.PATH + ProblemProbeController.HANDLER_FAILURE)
				.exchange();

		assertThat(result).hasStatus(500);
		assertThatIsAProblemDocument(result, ProblemType.INTERNAL_ERROR);
		assertThat(result).bodyText().doesNotContain(ProblemProbeController.LEAKED_INTERNAL_DETAIL);
	}

	/**
	 * The distinction later tickets rely on: two refusals with the same status, told
	 * apart by their URN, and only one of them worth retrying.
	 */
	@Nested
	@DisplayName("Retry-After")
	class RetryAfter {

		@Test
		@DisplayName("is present where retrying will help")
		void isPresentWhereRetryingWillHelp() {
			MvcTestResult result = mvc.get()
					.uri(ProblemProbeController.PATH + ProblemProbeController.RETRYABLE_REFUSAL)
					.exchange();

			assertThat(result).hasStatus(409);
			assertThatIsAProblemDocument(result, ProblemType.REQUEST_IN_PROGRESS);
			assertThat(result).headers()
					.hasSingleValue(HttpHeaders.RETRY_AFTER, ProblemProbeController.RETRY_AFTER_SECONDS);
		}

		@Test
		@DisplayName("is absent where it will not")
		void isAbsentWhereItWillNot() {
			MvcTestResult result = mvc.get()
					.uri(ProblemProbeController.PATH + ProblemProbeController.PERMANENT_REFUSAL)
					.exchange();

			assertThat(result).hasStatus(409);
			assertThatIsAProblemDocument(result, ProblemType.IDEMPOTENCY_KEY_REUSED);
			assertThat(result).doesNotContainHeader(HttpHeaders.RETRY_AFTER);
		}
	}

	/**
	 * One shape, one discriminator. {@code about:blank} is what the framework fills
	 * {@code type} with when nobody names the failure, and it is the value that would
	 * force a client back onto branching on the status code.
	 */
	private static void assertThatIsAProblemDocument(MvcTestResult result, ProblemType expected) {
		assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(expected.urn());
		assertThat(result).bodyJson().extractingPath("$.title").isNotNull();
		assertThat(result).bodyJson().extractingPath("$.detail").isNotNull();
		assertThat(result).bodyJson().extractingPath("$.status").isNotNull();
	}
}
