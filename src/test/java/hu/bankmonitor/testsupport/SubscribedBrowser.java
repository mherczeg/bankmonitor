package hu.bankmonitor.testsupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.stream.Stream;

/**
 * One browser's end of the event stream, over a real socket.
 *
 * <p>{@link HttpClient} rather than the {@code RestTestClient} the rest of the suite uses,
 * because every one of those reads a response that ends. This one does not: the assertion is
 * about what arrives while the connection stays open, so the body is taken as a lazy
 * {@link Stream} of lines and read a frame at a time.
 *
 * <p><b>A subscription is established by the time {@link #subscribeTo} returns</b>, which is
 * what lets the tests publish immediately afterwards with nothing to wait for. It holds
 * because the endpoint registers the browser and only then writes the comment that flushes
 * the response, so a header reaching this client is proof the registry already has it. That
 * is a property of this application rather than of Spring — an emitter nothing is written to
 * leaves the response sitting in the container's buffer, and a client waits on headers that
 * never come. If the write on subscription were dropped these tests would hang rather than
 * flake, which is the failure worth having.
 *
 * <p>HTTP/1.1 explicitly. The default client negotiates upward, and a version this test does
 * not control decides whether two subscriptions share one connection — which is the whole
 * subject of the disconnection test below it.
 */
public final class SubscribedBrowser implements AutoCloseable {

	private static final String STREAM = "/api/events/stream";

	/** The one SSE field a message is allowed to arrive in. */
	private static final String DATA_FIELD = "data:";

	/**
	 * A comment — the line the endpoint opens the stream with. Skipped here exactly as
	 * {@code EventSource} discards it, and the only non-{@code data} line that is.
	 */
	private static final String COMMENT = ":";

	private final HttpClient connection;

	private final HttpResponse<Stream<String>> response;

	private final Iterator<String> lines;

	private SubscribedBrowser(HttpClient connection, HttpResponse<Stream<String>> response) {
		this.connection = connection;
		this.response = response;
		this.lines = response.body().iterator();
	}

	/** Opens the stream on a running server and returns once the response has begun. */
	public static SubscribedBrowser subscribeTo(int port) throws Exception {
		HttpClient connection = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
		HttpRequest subscription = HttpRequest.newBuilder(URI.create("http://localhost:" + port + STREAM))
				.GET()
				.build();
		return new SubscribedBrowser(connection, connection.send(subscription, HttpResponse.BodyHandlers.ofLines()));
	}

	public int status() {
		return response.statusCode();
	}

	public String contentType() {
		return response.headers().firstValue("Content-Type").orElse("");
	}

	/**
	 * Blocks until the next message arrives and returns its body, or fails if the stream ends
	 * first.
	 *
	 * <p>Blocking rather than polling is what makes a test using this deterministic: it waits
	 * on the event itself and nothing else, so there is no interval to tune and no sleep that
	 * is either flaky or slow. A test that would otherwise hang for ever is bounded by JUnit's
	 * own timeout, stated by its caller.
	 *
	 * <p><b>A frame carrying any field other than {@code data} fails here rather than being
	 * skipped</b>, and that is the one assertion no caller states for itself. The frontend
	 * reads this stream with {@code EventSource}'s {@code onmessage}, which is dispatched only
	 * for the <em>default</em> event — so naming the SSE event would leave a deployed
	 * {@code events.ts} deaf while every assertion about a message's content stayed green,
	 * because a reader that skips what it does not recognise cannot see the difference. The
	 * same holds for an {@code id} field: design decision 17 declines {@code Last-Event-ID},
	 * and a stream that started sending one would be offering a catch-up nothing implements.
	 * Every caller of this method inherits the guarantee without asking for it.
	 */
	public String nextMessage() {
		while (lines.hasNext()) {
			String line = lines.next();
			if (line.startsWith(DATA_FIELD)) {
				return line.substring(DATA_FIELD.length());
			}
			if (!line.isEmpty() && !line.startsWith(COMMENT)) {
				throw new AssertionError(
						"a browser dispatches only the default event, and this frame carries " + line);
			}
		}
		throw new AssertionError("the stream ended before it carried a message");
	}

	/**
	 * The bytes a message of one type about one Transfer arrives as.
	 *
	 * <p>Built here so the JSON is written once, and built from a {@code String} rather than
	 * from {@link hu.bankmonitor.payments.stream.TransferEventType} on purpose: the contract
	 * this pins is with a deployed {@code events.ts} that this repository's compiler cannot
	 * see, so an expected frame that renamed itself along with the enum would agree with any
	 * rename and prove only that the application agrees with itself. The type stays a literal
	 * at the call site; only the punctuation is shared.
	 */
	public static String frameFor(String type, long transferId) {
		return "{\"type\":\"" + type + "\",\"transferId\":" + transferId + "}";
	}

	/** Goes away the way a closed tab does, without telling the server first. */
	@Override
	public void close() {
		response.body().close();
		connection.close();
	}
}
