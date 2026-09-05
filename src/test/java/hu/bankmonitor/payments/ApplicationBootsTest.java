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

	/**
	 * The datasource is part of the health aggregate, so an overall {@code UP} also says
	 * H2 was reachable rather than merely configured.
	 */
	@Test
	@DisplayName("the health endpoint reports UP")
	void healthEndpointReportsUp() {
		client().get().uri("/actuator/health")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.status").isEqualTo("UP")
				.jsonPath("$.components.db.status").isEqualTo("UP");
	}

	/**
	 * Asks the running container what kind of thread served the request, rather than
	 * asserting {@code spring.threads.virtual.enabled} is set, which would only restate
	 * the configuration file. Flipping the property off fails this test.
	 */
	@Test
	@DisplayName("requests are served on virtual threads")
	void servesRequestsOnVirtualThreads() {
		client().get().uri(ThreadProbeController.PATH)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.virtual").isEqualTo(true);
	}
}
