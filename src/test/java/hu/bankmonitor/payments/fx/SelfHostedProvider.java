package hu.bankmonitor.payments.fx;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/**
 * Points the Exchange Rate client at the stand-in provider this same application serves.
 *
 * <p>The client needs its provider's base URL <em>while the context is being built</em>,
 * and {@code local.server.port} is only published once the server has started, so
 * {@code WebEnvironment.RANDOM_PORT} cannot supply one. A port is therefore chosen up
 * front and the server told to take it, which is the one arrangement in which the two
 * halves of a self-call can agree on an address.
 */
final class SelfHostedProvider {

	/**
	 * Spelled out rather than read from {@code MockExchangeRateProvider}, which is
	 * package-private in a package this one cannot see — and deliberately so. A test of the
	 * client names the profile the way a deployment does, in a string, because that is the
	 * only handle a deployment has on it either.
	 */
	static final String PROFILE = "mock-fx";

	private SelfHostedProvider() {
	}

	/**
	 * Give every test class its own port. Spring keeps a context cached and running while
	 * the next one starts, so two classes sharing a port would collide on the second.
	 */
	static void onItsOwnPort(DynamicPropertyRegistry registry) {
		int port = freePort();
		registry.add("server.port", () -> port);
		registry.add("payments.fx.base-url", () -> "http://localhost:" + port + "/mock");
	}

	/**
	 * Asks the OS for a port by binding one and letting it go, which leaves a window
	 * between the release and Tomcat's bind in which something else could take it. The
	 * alternative is handing Tomcat the listening socket, which
	 * {@code WebEnvironment.DEFINED_PORT} gives no way to do. The cost is named rather than
	 * hidden: this is the one thing in the slice that can fail without anything being
	 * wrong, and a rerun is the whole remedy.
	 */
	private static int freePort() {
		try (ServerSocket probe = new ServerSocket(0)) {
			return probe.getLocalPort();
		}
		catch (IOException unavailable) {
			throw new UncheckedIOException("no free port to host the stand-in provider on", unavailable);
		}
	}
}
