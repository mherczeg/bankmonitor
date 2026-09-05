package hu.bankmonitor.payments;

import hu.bankmonitor.testsupport.BootedApplicationTest;
import hu.bankmonitor.testsupport.ThreadProbeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The application starts on H2 and answers over real HTTP.
 *
 * <p>Shares its application context with {@code OpenApiDocumentSpikeTest} by way of
 * {@link BootedApplicationTest}, so the application boots once for both.
 */
class ApplicationBootsTest extends BootedApplicationTest {

	@Test
	@DisplayName("the health endpoint reports UP")
	void healthEndpointReportsUp() {
		client().get().uri("/actuator/health")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.status").isEqualTo("UP")
				// The datasource is part of the health aggregate, so an UP overall status
				// also says H2 was reachable rather than merely configured.
				.jsonPath("$.components.db.status").isEqualTo("UP");
	}

	@Test
	@DisplayName("requests are served on virtual threads")
	void servesRequestsOnVirtualThreads() {
		// `spring.threads.virtual.enabled=true` is load-bearing rather than a nicety: the
		// stand-in Exchange Rate provider is a real HTTP endpoint inside this same
		// application, so serving a transfer means one request thread waits on a second
		// thread of this same server. Asserting the property is set would only restate
		// the configuration file; asking the container what it actually did is the claim
		// worth testing. Flip the property to false and this test fails, which is the
		// point of writing it this way round.
		client().get().uri(ThreadProbeController.PATH)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.virtual").isEqualTo(true);
	}
}
