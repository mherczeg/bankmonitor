package hu.bankmonitor.payments;

import hu.bankmonitor.payments.common.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;

/**
 * Answers a refusal from the filter chain with the same RFC 9457 document every other error
 * in this API arrives as.
 *
 * <p>Until the shared secret there was nothing to write this for: the chain denied paths it
 * does not name, which is a caller asking for something that does not exist, and a bodiless
 * {@code 403} answers that completely. The secret produces the first refusal a client is
 * <em>meant to read</em> — an operator wiring up a Check service against the wrong secret —
 * and a client that has learnt to parse this API's failures should not meet an empty body
 * exactly where it most needs to know what happened.
 *
 * <p><b>The document is written here rather than by {@code ProblemDocumentAdvice}, because a
 * denial never reaches the dispatcher.</b> It is raised by a filter in front of it, so no
 * {@code @ControllerAdvice} can see it, and nothing downstream will turn a return value into
 * a response. Writing it through the same kind of converter Spring MVC returns a
 * {@link ProblemDetail} through — over the application's own configured mapper — is what
 * keeps the two shapes identical rather than merely similar.
 *
 * <p><b>{@code 403} rather than {@code 401}</b>, for missing and wrong secrets alike. RFC
 * 9110 obliges a {@code 401} to carry a {@code WWW-Authenticate} challenge naming an HTTP
 * authentication scheme; a shared secret in a bespoke header is not one, so the challenge
 * would either be absent — making the response malformed — or name a scheme this API does not
 * accept, sending a client to retry in a way that cannot work. {@code 403} claims no scheme,
 * which is the honest description of the rule.
 *
 * <p>It implements both entry point and handler because Spring Security chooses between them
 * on whether the caller is anonymous. This application authenticates nobody, so today only
 * {@link #commence} runs — and the pair is written as one class so that the day something
 * does authenticate, the other path is not an empty {@code 403} nobody noticed.
 */
class DeniedAsAProblemDocument implements AuthenticationEntryPoint, AccessDeniedHandler {

	private static final String DETAIL = "This endpoint is internal and requires a valid secret.";

	private final JacksonJsonHttpMessageConverter json;

	/**
	 * @param mapper the mapper the rest of this application serialises through. The converter
	 *               is built here rather than injected because Spring Boot registers no
	 *               converter bean of this type — MVC's converters are assembled inside its
	 *               own configuration — and a converter over the configured mapper is the
	 *               part that has to match, not the instance
	 */
	DeniedAsAProblemDocument(JsonMapper mapper) {
		this.json = new JacksonJsonHttpMessageConverter(mapper);
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException denial) throws IOException {
		write(request, response);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException denial) throws IOException {
		write(request, response);
	}

	/**
	 * The refusal carries no property naming what was wrong with the credential, and the
	 * {@code detail} is one sentence that reads the same whether a secret was absent, stale or
	 * meant for another service. That uniformity is the rule rather than an economy: a
	 * response that distinguished them would answer a caller probing for the header.
	 *
	 * <p>{@code instance} is set here, unlike everywhere else in this application, because the
	 * converter that fills it in from the request URI is Spring MVC's return-value handling —
	 * which a denial, raised before the dispatcher, never reaches.
	 */
	private void write(HttpServletRequest request, HttpServletResponse response) throws IOException {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, DETAIL);
		problem.setType(ProblemType.FORBIDDEN.uri());
		problem.setInstance(URI.create(request.getRequestURI()));

		response.setStatus(HttpStatus.FORBIDDEN.value());
		json.write(problem, MediaType.APPLICATION_PROBLEM_JSON, new ServletServerHttpResponse(response));
	}
}
