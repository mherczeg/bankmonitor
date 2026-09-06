package hu.bankmonitor.payments;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * The one filter chain: stateless, cross-origin to the frontend, denying what it does not
 * name.
 *
 * <p>This application has no authentication and no principal — design decision 2 declined
 * to model a user, and nothing downstream learns who called. That deferral is why the
 * chain exists at all: an absent dependency and a deliberate decision are
 * indistinguishable in a running application, and only one of them denies an
 * unrecognised path. The one real authorization rule now sits on it too: ticket 21 put
 * {@code /internal/**} behind {@link SharedSecretAuthorization} (design decision 10).
 *
 * <p>It sits in the root package beside the application class because it belongs to no
 * slice; the domain packages own their own behaviour, not the chain in front of it.
 *
 * <p>Public only so that a web-slice test in another package can {@code @Import} it. That is
 * the point of the rule being here: a slice test of an internal endpoint that could not name
 * this class would have to assert the mapping against a default chain and silently drop the
 * one authorization rule this application has.
 *
 * <p>See design decision 2 for the alternatives this was chosen over, and design decision 21
 * for the shared secret.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

	private static final String PUBLIC_API = "/api/**";

	/**
	 * The prefix rather than the one endpoint under it, so that a second internal endpoint
	 * inherits the secret instead of having to remember it — and the one that forgot would
	 * otherwise be the open one.
	 */
	private static final String INTERNAL_API = "/internal/**";

	private static final String HEALTH_CHECK = "/actuator/health";

	private static final String[] API_DOCUMENTATION = {
			"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

	/** Named in {@code CONTEXT.md}; ticket 16 gives it a constant in the slice that reads it. */
	private static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";

	/**
	 * Both collaborators arrive as method parameters rather than as beans of their own, so
	 * that importing this class is the whole of what a web-slice test needs in order to run
	 * against the real policy instead of a second copy of it.
	 *
	 * @param internalSecret the shared secret {@link #INTERNAL_API} is guarded by
	 * @param mapper         the mapper a refusal's problem document is written through, which
	 *                       is what makes it the same shape as every other error in this API
	 *                       rather than merely a similar one
	 */
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Value("${payments.internal.shared-secret}") String internalSecret,
			JsonMapper mapper) throws Exception {
		DeniedAsAProblemDocument denied = new DeniedAsAProblemDocument(mapper);
		return http
				// No cookie, no Authorization header, no credentials on a cross-origin call:
				// a CSRF token would defend ambient authority that does not exist here.
				.csrf(AbstractHttpConfigurer::disable)
				// Security runs ahead of MVC and answers the CORS preflight itself, so this
				// entry is required and a @CrossOrigin annotation would never be reached.
				.cors(Customizer.withDefaults())
				// Nothing is remembered between requests, so a session would be state with no
				// reader — and a cookie the CSRF decision above assumes is absent.
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// The entry point below would otherwise have the request cache save the denied
				// request first, and saving it creates the session the line above says this
				// application does not have.
				.requestCache(RequestCacheConfigurer::disable)
				.authorizeHttpRequests(request -> request
						// Authorization runs on the ERROR dispatch too, where denying turns every
						// 404 and 405 into a 403 — and, since the entry point below, into one
						// carrying a document that names a credential nobody was asked for.
						// ASYNC continues a request already authorized on its way in.
						.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
						.requestMatchers(PUBLIC_API).permitAll()
						.requestMatchers(HEALTH_CHECK).permitAll()
						.requestMatchers(API_DOCUMENTATION).permitAll()
						.requestMatchers(INTERNAL_API).access(new SharedSecretAuthorization(internalSecret))
						.anyRequest().denyAll())
				// A denial is raised in front of the dispatcher, so no @ControllerAdvice can
				// see it and turn it into a problem document. This is where that happens
				// instead.
				.exceptionHandling(refusals -> refusals
						.authenticationEntryPoint(denied)
						.accessDeniedHandler(denied))
				.build();
	}

	/**
	 * The browser-facing half of the policy, mapped onto {@link #PUBLIC_API} alone: the
	 * mock FX provider stands in for a third party reached server-to-server, and putting it
	 * under our CORS policy would undo the isolation it exists for (design decision 28).
	 *
	 * @param allowedOrigins the origins allowed to call this API from a browser, defaulting
	 *                       to the Vite dev server that design decision 20 runs as a
	 *                       separate process
	 */
	@Bean
	CorsConfigurationSource corsConfigurationSource(
			@Value("${payments.cors.allowed-origins}") List<String> allowedOrigins) {
		CorsConfiguration policy = new CorsConfiguration();
		policy.setAllowedOrigins(allowedOrigins);
		policy.setAllowedMethods(List.of(HttpMethod.GET.name(), HttpMethod.POST.name()));
		policy.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE, IDEMPOTENCY_KEY_HEADER));
		// Only CORS-safelisted response headers reach cross-origin JavaScript, and
		// Retry-After carries the retry policy of design decision 18.
		policy.setExposedHeaders(List.of(HttpHeaders.RETRY_AFTER));
		// The other half of the CSRF decision above: no ambient authority crosses origins.
		policy.setAllowCredentials(false);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration(PUBLIC_API, policy);
		return source;
	}
}
