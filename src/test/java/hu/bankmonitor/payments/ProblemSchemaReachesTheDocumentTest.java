package hu.bankmonitor.payments;

import hu.bankmonitor.payments.common.ProblemType;
import hu.bankmonitor.testsupport.BootedApplicationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the problem-type vocabulary reaches {@code /v3/api-docs}, which is the only route by
 * which it reaches the frontend (ticket 32).
 *
 * <p>The frontend branches on the {@code type} URN and on nothing else, and it gets the set
 * of URNs it may branch on from the generated types. A URN this application starts or stops
 * emitting therefore has to move the published document, or the frontend keeps compiling
 * against a vocabulary that no longer exists — which is the failure the generated types are
 * bought to prevent.
 */
class ProblemSchemaReachesTheDocumentTest extends BootedApplicationTest {

	private static final String OPENAPI_DOCUMENT = "/v3/api-docs";

	private static final String PROBLEM_DOCUMENT_REFERENCE = "#/components/schemas/ProblemDocument";

	@Test
	@DisplayName("the document offers every problem type as a closed set of URNs")
	void publishesEveryProblemTypeAsAClosedSet() {
		List<String> everyUrn = Arrays.stream(ProblemType.values()).map(ProblemType::urn).toList();

		client().get().uri(OPENAPI_DOCUMENT)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.components.schemas.ProblemType.enum")
				.value(List.class, urns -> assertThat(urns).containsExactlyInAnyOrderElementsOf(everyUrn));
	}

	/**
	 * The URN is reachable from the document shape rather than only sitting in
	 * {@code components}: a generator emits the enumeration as a type of its own, and the
	 * {@code type} member has to be that type rather than a bare string, or a client is free
	 * to branch on a URN this API never sends.
	 */
	@Test
	@DisplayName("the problem document's type member is the enumeration, not a string")
	void namesTheEnumerationOnTheTypeMember() {
		client().get().uri(OPENAPI_DOCUMENT)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.components.schemas.ProblemDocument.properties.type.$ref")
				.isEqualTo("#/components/schemas/ProblemType")
				.jsonPath("$.components.schemas.ProblemDocument.required")
				.value(List.class, required ->
						assertThat(required).containsExactlyInAnyOrder("type", "title", "status", "detail", "instance"))
				.jsonPath("$.components.schemas.ProblemDocument.properties.errors.items.$ref")
				.isEqualTo("#/components/schemas/ValidationError");
	}

	/**
	 * A violation of a class-level rule belongs to the request rather than to any one
	 * input, and arrives with a null field rather than with the member left out. A client
	 * that had been told the member was merely optional would read it as a string and find
	 * a null.
	 */
	@Test
	@DisplayName("a validation error's field is nullable rather than absent")
	void publishesTheFieldAsNullable() {
		client().get().uri(OPENAPI_DOCUMENT)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.components.schemas.ValidationError.properties.field.type")
				.value(List.class, types -> assertThat(types).containsExactly("string", "null"))
				.jsonPath("$.components.schemas.ValidationError.required")
				.value(List.class, required -> assertThat(required).containsExactlyInAnyOrder("field", "message"));
	}

	/**
	 * A schema no operation refers to is a schema a generator is entitled to drop. Attaching
	 * the response is also the honest description: every status an operation does not name
	 * is this document.
	 */
	@Test
	@DisplayName("every operation answers anything it does not name with a problem document")
	void attachesTheProblemDocumentToEveryOperation() {
		client().get().uri(OPENAPI_DOCUMENT)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.paths./api/accounts.get.responses.default.content.['application/problem+json'].schema.$ref")
				.isEqualTo(PROBLEM_DOCUMENT_REFERENCE)
				.jsonPath("$.paths./api/accounts.post.responses.default.content.['application/problem+json'].schema.$ref")
				.isEqualTo(PROBLEM_DOCUMENT_REFERENCE);
	}
}
