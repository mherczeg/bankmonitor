package hu.bankmonitor.testsupport;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports whether the thread serving the request is a virtual thread.
 *
 * <p>Deliberately outside {@code hu.bankmonitor.payments} so Spring's component scan
 * never picks it up on its own. Test sources land on the same classpath as main
 * sources, so a {@code @RestController} placed under the application's base package
 * would silently register itself in every {@code @SpringBootTest} in the suite. Tests
 * that want this one {@code @Import} it explicitly.
 *
 * <p>Two tests need it: {@code ApplicationBootsTest} asks it what kind of thread it is
 * running on, and {@code OpenApiDocumentSpikeTest} needs at least one real controller
 * to exist, because an OpenAPI document with no paths in it would prove very little.
 */
@RestController
public class ThreadProbeController {

	public static final String PATH = "/api/test-probe/thread";

	/** @return the kind of thread Tomcat handed this request to, and its name */
	@GetMapping(PATH)
	public ThreadReport thread() {
		Thread current = Thread.currentThread();
		return new ThreadReport(current.isVirtual(), current.getName());
	}

	public record ThreadReport(boolean virtual, String name) {
	}
}
