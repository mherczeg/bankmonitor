package hu.bankmonitor.testsupport;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports whether the thread serving the request is a virtual thread.
 *
 * <p>Deliberately outside {@code hu.bankmonitor.payments} so component scan never picks
 * it up on its own: test sources share the runtime classpath, so a {@code @RestController}
 * under the application's base package would silently register itself in every
 * {@code @SpringBootTest} in the suite. {@link BootedApplicationTest} imports it
 * explicitly.
 *
 * <p>Two tests need it: one asks it what kind of thread it is running on, and the OpenAPI
 * spike needs a real controller to exist, since a document with no paths would prove very
 * little.
 */
@RestController
public class ThreadProbeController {

	public static final String PATH = "/api/test-probe/thread";

	@GetMapping(PATH)
	public ThreadReport thread() {
		Thread current = Thread.currentThread();
		return new ThreadReport(current.isVirtual(), current.getName());
	}

	public record ThreadReport(boolean virtual, String name) {
	}
}
