package hu.bankmonitor.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Base for tests that need the whole application running on a real port.
 *
 * <p>The configuration lives here rather than being repeated on each subclass for a
 * reason beyond tidiness: Spring caches one application context per distinct
 * configuration, so two classes differing by a single annotation pay two boots. Holding
 * the annotations in one place makes the shared context structural rather than a
 * coincidence the next edit could break.
 *
 * <p>{@code RANDOM_PORT} rather than MockMvc, because the claims these tests make are
 * about the servlet container itself — that a request reaches a listening socket, and
 * what kind of thread Tomcat serves it on. MockMvc would bypass it and prove neither.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ThreadProbeController.class)
public abstract class BootedApplicationTest {

	@LocalServerPort
	private int port;

	/**
	 * A client bound to the running server. {@code TestRestTemplate} is gone in Spring
	 * Boot 4; {@code RestTestClient} is its Spring Framework 7 replacement.
	 */
	protected RestTestClient client() {
		return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}
}
