package hu.bankmonitor.payments.mockfx;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;

/**
 * The stand-in provider's own configuration: the profile that switches it on, the
 * namespace it answers under, and the one thing it needs this application to <em>stop</em>
 * doing on its behalf.
 *
 * <p>Every bean of the provider is gated on {@link #PROFILE}, this one included, so
 * without the profile the endpoint is gone and {@code /mock/**} goes back to being a path
 * the security chain does not name — which it denies. A bypass that outlived the endpoint
 * it exists for would be a hole nothing was watching.
 */
@Configuration
@Profile(MockExchangeRateProvider.PROFILE)
@EnableConfigurationProperties(FlakinessSettings.class)
class MockExchangeRateProvider {

	static final String PROFILE = "mock-fx";

	/** Everything the stand-in serves lives under here, so one pattern takes all of it out of the chain. */
	static final String NAMESPACE = "/mock";

	private static final String EVERYTHING_UNDER_THE_NAMESPACE = NAMESPACE + "/**";

	/**
	 * A total bypass rather than {@code permitAll}: the provider is not an endpoint of
	 * this application that everyone is allowed to call, it is an endpoint of somebody
	 * else's application that ours happens to be hosting.
	 *
	 * <p>Under {@code permitAll} the request still passes through the filter chain and
	 * comes back wearing our security response headers, which is our server signing a
	 * third party's response. {@code ignoring()} takes it out of the chain altogether.
	 *
	 * <p>Spring Security logs a warning at startup for exactly this, advising
	 * {@code permitAll} instead. The advice is right for endpoints that are ours and
	 * wrong for this one, and the warning appears only under a profile whose entire
	 * purpose is to pretend.
	 */
	@Bean
	WebSecurityCustomizer mockProviderIsNotOursToSecure() {
		return web -> web.ignoring().requestMatchers(EVERYTHING_UNDER_THE_NAMESPACE);
	}
}
