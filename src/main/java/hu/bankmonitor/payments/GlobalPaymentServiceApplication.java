package hu.bankmonitor.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * The exclusion removes the in-memory user Boot would otherwise generate, whose random
 * password is logged at every startup under a warning to replace it before production.
 * Nothing here authenticates, so the account is unusable and reads as configuration left
 * unfinished — the impression {@link SecurityConfiguration} exists to avoid.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class GlobalPaymentServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(GlobalPaymentServiceApplication.class, args);
	}

	/**
	 * The one source of "now" this application reads, injected rather than called
	 * statically so that a test can hold it still. Every deadline in the design is measured
	 * from an instant stamped on a Transfer — the check deadline of ticket 23 above all —
	 * and a test that had to sleep through one would be a slow test that still could not
	 * say what it was waiting for.
	 *
	 * <p>It belongs to no slice, so it sits here beside the other two things that do not:
	 * the security chain and the problem document advice. Boot auto-configures no
	 * {@link Clock}, which is why this is declared rather than merely injected.
	 */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
