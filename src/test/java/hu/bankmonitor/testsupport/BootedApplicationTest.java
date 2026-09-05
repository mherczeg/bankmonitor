package hu.bankmonitor.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Base for tests that need the whole application running on a real port.
 *
 * <p>The configuration lives here rather than being repeated on each subclass for a
 * reason beyond tidiness. Spring caches an application context per distinct
 * configuration, so two test classes that differ by even one annotation pay two boots
 * of the application. Holding the annotations in one place makes the shared context
 * structural instead of a coincidence that the next edit could quietly break.
 *
 * <p>Uses {@code RANDOM_PORT} rather than MockMvc because the claims these tests make
 * are about the servlet container itself: that a request reaches a listening socket,
 * and what kind of thread Tomcat serves it on. MockMvc bypasses the container and would
 * prove neither.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ThreadProbeController.class)
public abstract class BootedApplicationTest {

	@LocalServerPort
	private int port;

	/**
	 * A client bound to the running server.
	 *
	 * <p>{@code TestRestTemplate} is gone in Spring Boot 4. {@code RestTestClient}, new
	 * in Spring Framework 7, is the replacement -- worth stating because every tutorial
	 * still reaches for the old one.
	 */
	protected RestTestClient client() {
		return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}
}
