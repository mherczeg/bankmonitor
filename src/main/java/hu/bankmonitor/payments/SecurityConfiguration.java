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
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The one filter chain: stateless, cross-origin to the frontend, denying what it does not
 * name.
 *
 * <p>This application has no authentication and no principal — design decision 2 declined
 * to model a user, and nothing downstream learns who called. That deferral is why the
 * chain exists at all: an absent dependency and a deliberate decision are
 * indistinguishable in a running application, and only one of them denies an
 * unrecognised path. The one real authorization rule arrives with the endpoint it
 * protects (design decision 10).
 *
 * <p>It sits in the root package beside the application class because it belongs to no
 * slice; the domain packages own their own behaviour, not the chain in front of it.
 *
 * <p>See design decision 2 for the alternatives this was chosen over.
 */
@Configuration
@EnableWebSecurity
class SecurityConfiguration {

	private static final String PUBLIC_API = "/api/**";

	private static final String HEALTH_CHECK = "/actuator/health";

	private static final String[] API_DOCUMENTATION = {
			"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

	/** Named in {@code CONTEXT.md}; ticket 16 gives it a constant in the slice that reads it. */
	private static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
				.authorizeHttpRequests(request -> request
						// Authorization runs on the ERROR dispatch too, where denying turns every
						// 404 and 405 into an empty 403. ASYNC continues a request already
						// authorized on its way in.
						.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
						.requestMatchers(PUBLIC_API).permitAll()
						.requestMatchers(HEALTH_CHECK).permitAll()
						.requestMatchers(API_DOCUMENTATION).permitAll()
						.anyRequest().denyAll())
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
