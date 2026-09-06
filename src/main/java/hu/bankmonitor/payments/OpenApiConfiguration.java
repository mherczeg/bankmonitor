package hu.bankmonitor.payments;

import hu.bankmonitor.payments.common.ProblemType;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Puts the error half of this API into the OpenAPI document, which is where the frontend's
 * types are generated from (design decision 26).
 *
 * <p>springdoc describes what a controller method returns, and no controller here returns a
 * problem document — {@link ProblemDocumentAdvice} does, after the handler has thrown. So
 * without this bean the published document describes every success shape and stays silent
 * about the one shape every failure arrives as, and a frontend generating from it would
 * have to hand-write the thing it generates types to avoid hand-writing.
 *
 * <p>The {@code ProblemType} enumeration is built from {@link ProblemType#values()} rather
 * than listed here. Design decision 18's claim is that the vocabulary exists in exactly one
 * place; a list written out in this file would be the second copy, and the copy that goes
 * stale silently.
 *
 * <p>It sits in the root package beside {@link SecurityConfiguration} and
 * {@link ProblemDocumentAdvice}, for the same reason they do: the error contract belongs to
 * no slice.
 */
@Configuration
class OpenApiConfiguration {

	private static final String PROBLEM_TYPE = "ProblemType";

	private static final String PROBLEM_DOCUMENT = "ProblemDocument";

	private static final String VALIDATION_ERROR = "ValidationError";

	/**
	 * The response key meaning "any status this operation does not name". It is the literal
	 * truth of this API rather than a shortcut: {@link ProblemDocumentAdvice} funnels every
	 * failure through one shape, so there is no status for which a caller would parse
	 * something else.
	 */
	private static final String ANY_OTHER_STATUS = "default";

	// Fully qualified on purpose: MediaType here is swagger's, imported above for the response body.
	private static final String PROBLEM_MEDIA_TYPE =
			org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	@Bean
	OpenApiCustomizer problemDocuments() {
		return document -> {
			document.schema(PROBLEM_TYPE, problemTypes());
			document.schema(VALIDATION_ERROR, validationError());
			document.schema(PROBLEM_DOCUMENT, problemDocument());
			document.getPaths().values().stream()
					.flatMap(path -> path.readOperations().stream())
					.forEach(operation ->
							operation.getResponses().addApiResponse(ANY_OTHER_STATUS, problemResponse()));
		};
	}

	private static Schema<String> problemTypes() {
		StringSchema types = new StringSchema();
		types.setDescription("The URN a client branches on, and the only member of the document it may branch on.");
		Arrays.stream(ProblemType.values()).map(ProblemType::urn).forEach(types::addEnumItemObject);
		return types;
	}

	/**
	 * The RFC 9457 members this API always sends, plus the {@code errors} extension that
	 * carries a rejected request's per-field breakdown.
	 *
	 * <p>Only {@code errors} is optional — it is there when a request was rejected field by
	 * field and absent otherwise. The other five are sent on every failure, which is what
	 * {@code ProblemDocumentContractTest} holds the running application to, and saying so
	 * here is what stops a frontend mock from omitting one and still compiling.
	 *
	 * <p>The member names are written as literals because they are RFC 9457's vocabulary
	 * rather than this codebase's: they cannot be renamed here, so a shared constant would
	 * name nothing that could move. {@code errors} is the exception, and it reads its name
	 * from the advice that writes it.
	 */
	private static Schema<Object> problemDocument() {
		Schema<Object> problem = new ObjectSchema();
		problem.setDescription("The one shape every failure of this API arrives as.");
		problem.addProperty("type", new Schema<String>().$ref(PROBLEM_TYPE));
		problem.addProperty("title", new StringSchema());
		problem.addProperty("status", new IntegerSchema().format("int32"));
		problem.addProperty("detail", new StringSchema());
		problem.addProperty("instance", new StringSchema());
		problem.addProperty(ProblemDocumentAdvice.VALIDATION_ERRORS,
				new ArraySchema().items(new Schema<>().$ref(VALIDATION_ERROR)));
		problem.setRequired(List.of("type", "title", "status", "detail", "instance"));
		return problem;
	}

	/**
	 * One rejected value. {@code field} is nullable rather than optional because a
	 * violation of a class-level rule belongs to the request rather than to any one input,
	 * and the advice reports it with a null field rather than omitting the member.
	 *
	 * <p>The nullability is written as a union of types because this document is OpenAPI
	 * 3.1, where {@code nullable: true} is not a keyword: swagger accepts
	 * {@code setNullable} and then silently drops it on the way out.
	 */
	private static Schema<Object> validationError() {
		StringSchema field = new StringSchema();
		field.setTypes(new LinkedHashSet<>(List.of("string", "null")));

		Schema<Object> error = new ObjectSchema();
		error.setDescription("A single rejected value, against the field that carried it.");
		error.addProperty("field", field);
		error.addProperty("message", new StringSchema());
		error.setRequired(List.of("field", "message"));
		return error;
	}

	private static ApiResponse problemResponse() {
		return new ApiResponse()
				.description("A problem document, named by its type URN.")
				.content(new Content().addMediaType(PROBLEM_MEDIA_TYPE,
						new MediaType().schema(new Schema<>().$ref(PROBLEM_DOCUMENT))));
	}
}
