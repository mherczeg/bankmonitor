package hu.bankmonitor.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

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

}
