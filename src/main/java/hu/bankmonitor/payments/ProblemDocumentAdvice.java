package hu.bankmonitor.payments;

import hu.bankmonitor.payments.common.ProblemType;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Gives every error this API emits a {@link ProblemType} URN, and validation failures a
 * field-level breakdown, on top of the RFC 9457 documents Spring already produces.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} rather than hand-rolling an error
 * envelope is the whole design (design decision 18). Spring answers {@code @Valid}
 * rejections, {@code 415}, {@code 405} and malformed JSON before any controller code
 * runs, so a custom shape would not replace those responses — it would ship a second
 * error format beside them. This class adopts the ones the framework makes and names
 * what they leave unnamed.
 *
 * <p>Declaring this bean is also what switches problem documents on: Boot's
 * auto-configured handler behind {@code spring.mvc.problemdetails.enabled} backs off in
 * the presence of any {@link ResponseEntityExceptionHandler}, so setting that property
 * as well would be a line of configuration with no reader.
 *
 * <p>It sits in the root package beside {@link SecurityConfiguration} because it belongs
 * to no slice; the vocabulary it hands out lives in {@code common}.
 */
@ControllerAdvice
class ProblemDocumentAdvice extends ResponseEntityExceptionHandler {

	/**
	 * The extension member carrying one entry per rejected field. Spring's default packs
	 * every violation into a single sentence in {@code detail}, which a form cannot mark
	 * up against the input that caused it.
	 */
	private static final String VALIDATION_ERRORS = "errors";

	private static final URI UNNAMED = URI.create("about:blank");

	private static final String INTERNAL_ERROR_DETAIL =
			"The request could not be completed. Nothing was changed.";

	private static final String NO_ENDPOINT_DETAIL = "This API has no endpoint at that path.";

	/**
	 * One violation, against the field that carries it. A violation from a class-level
	 * rule belongs to the request rather than to one input, and reports a null field.
	 */
	record Violation(@Nullable String field, String message) {
	}

	/** A rejected request body: one entry per rejected property. */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return amend(super.handleMethodArgumentNotValid(exception, headers, status, request),
				problem -> problem.setProperty(VALIDATION_ERRORS, violationsIn(exception.getBindingResult())));
	}

	/**
	 * The same breakdown for constraints on the method's own parameters — a query
	 * parameter, a path variable, the idempotency key header. Without this override the
	 * extension member would depend on <em>where</em> the rejected value arrived, and a
	 * client cannot see that distinction to branch on it.
	 */
	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return amend(super.handleHandlerMethodValidationException(exception, headers, status, request),
				problem -> problem.setProperty(VALIDATION_ERRORS, violationsIn(exception)));
	}

	/**
	 * The framework's wording for this one is <em>"No static resource api/nope."</em>,
	 * which names the handler that ran out of options rather than anything a caller can
	 * act on — and this API serves no static resources. The path is already in
	 * {@code instance}, so the detail does not repeat it back.
	 */
	@Override
	protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException exception,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return amend(super.handleNoResourceFoundException(exception, headers, status, request),
				problem -> problem.setDetail(NO_ENDPOINT_DETAIL));
	}

	/**
	 * The last resort, and the only handler here that is not the framework's own: an
	 * exception nothing else claims would otherwise leave the dispatcher for Boot's
	 * {@code /error} page, which is a different document shape and, being derived from
	 * the exception, says more about this server than a caller should learn.
	 */
	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleAnythingElse(Exception exception, WebRequest request) {
		logger.error("Unhandled exception, answered as " + ProblemType.INTERNAL_ERROR.urn(), exception);
		ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_DETAIL);
		body.setTitle(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
		return handleExceptionInternal(exception, body, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
	}

	/**
	 * Rethrown rather than answered, so Spring Security's own translation runs. Caught by
	 * the handler above it would become a {@code 500}, silently turning the first
	 * authorization rule this application grows into a server error.
	 */
	@ExceptionHandler(AccessDeniedException.class)
	void rethrowSoTheSecurityChainCanAnswer(AccessDeniedException denial) {
		throw denial;
	}

	/**
	 * Where the URN is attached, for the framework's failures and ours alike: every
	 * handler in this class and in the base class funnels through here. A document that
	 * already names its type keeps it — that is a slice's own refusal arriving.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception exception, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return amend(super.handleExceptionInternal(exception, body, headers, status, request), problem -> {
			if (isUnnamed(problem)) {
				problem.setType(typeOf(exception, status).uri());
			}
		});
	}

	private static @Nullable ResponseEntity<Object> amend(@Nullable ResponseEntity<Object> response,
			Consumer<ProblemDetail> amendment) {
		if (response != null && response.getBody() instanceof ProblemDetail problem) {
			amendment.accept(problem);
		}
		return response;
	}

	private static boolean isUnnamed(ProblemDetail problem) {
		return problem.getType() == null || UNNAMED.equals(problem.getType());
	}

	/**
	 * Mapped from the status rather than from the exception class, with one refinement:
	 * a body Jackson could not read and a body that failed its constraints are both
	 * {@code 400} and are not the same news. Reading the status also keeps a server fault
	 * that happens to carry a client-error exception type — {@code
	 * ConversionNotSupportedException} is one — from telling the caller its input was
	 * wrong.
	 */
	private static ProblemType typeOf(Exception exception, HttpStatusCode status) {
		if (!status.is4xxClientError()) {
			return ProblemType.INTERNAL_ERROR;
		}
		HttpStatus resolved = HttpStatus.resolve(status.value());
		if (resolved == null) {
			return ProblemType.CLIENT_ERROR;
		}
		return switch (resolved) {
			case BAD_REQUEST -> exception instanceof HttpMessageNotReadableException
					? ProblemType.MALFORMED_REQUEST
					: ProblemType.VALIDATION_FAILED;
			case NOT_FOUND -> ProblemType.NOT_FOUND;
			case METHOD_NOT_ALLOWED -> ProblemType.METHOD_NOT_ALLOWED;
			case UNSUPPORTED_MEDIA_TYPE -> ProblemType.UNSUPPORTED_MEDIA_TYPE;
			default -> ProblemType.CLIENT_ERROR;
		};
	}

	private static List<Violation> violationsIn(BindingResult rejected) {
		return sorted(rejected.getAllErrors().stream()
				.map(error -> new Violation(fieldOf(error), messageOf(error))));
	}

	private static List<Violation> violationsIn(HandlerMethodValidationException rejected) {
		Stream<Violation> perParameter = rejected.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(error -> new Violation(fieldOrParameterOf(error, result), messageOf(error))));
		Stream<Violation> acrossParameters = rejected.getCrossParameterValidationResults().stream()
				.map(error -> new Violation(null, messageOf(error)));
		return sorted(Stream.concat(perParameter, acrossParameters));
	}

	/**
	 * Sorted, because Bean Validation promises no order and a form listing its errors
	 * differently on every submission is a bug report waiting to be filed.
	 */
	private static List<Violation> sorted(Stream<Violation> violations) {
		return violations
				.sorted(Comparator.comparing(Violation::field, Comparator.nullsFirst(Comparator.naturalOrder()))
						.thenComparing(Violation::message))
				.toList();
	}

	/** The fallback exists so that a constraint declared without a message cannot turn a 400 into a 500. */
	private static String messageOf(MessageSourceResolvable error) {
		String message = error.getDefaultMessage();
		return message != null ? message : "Invalid value.";
	}

	private static @Nullable String fieldOf(MessageSourceResolvable error) {
		return error instanceof FieldError fieldError ? fieldError.getField() : null;
	}

	/** A constraint on the parameter itself has no field of its own; the parameter is one. */
	private static @Nullable String fieldOrParameterOf(MessageSourceResolvable error,
			ParameterValidationResult result) {
		String field = fieldOf(error);
		return field != null ? field : result.getMethodParameter().getParameterName();
	}
}
