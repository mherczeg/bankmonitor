package hu.bankmonitor.payments.stream;

import hu.bankmonitor.testsupport.BootedApplicationTest;
import hu.bankmonitor.testsupport.SubscribedBrowser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;

import static hu.bankmonitor.payments.stream.TransferEventType.TRANSFER_EXPIRED;
import static hu.bankmonitor.payments.stream.TransferEventType.TRANSFER_REJECTED;
import static hu.bankmonitor.payments.stream.TransferEventType.TRANSFER_SETTLED;
import static hu.bankmonitor.testsupport.SubscribedBrowser.frameFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code GET /api/events/stream} against a running server: what a browser is sent, that
 * every browser is sent it, and what happens to one that stops listening.
 *
 * <p>A real socket rather than MockMvc, for {@link BootedApplicationTest}'s reason applied to
 * the one endpoint where it matters most. The claim here is that bytes leave the container
 * <em>while the response is still open</em>, and MockMvc — which never opens a connection and
 * whose response is a buffer the test reads afterwards — would agree with an implementation
 * that collected every message and flushed them all at close. That is not a hypothetical
 * failure: ticket 31 measured exactly that shape one layer up, where the dev server's gzip
 * encoder held five events until the stream ended.
 *
 * <p>Nothing here sleeps or polls for a message. {@link SubscribedBrowser#nextMessage()}
 * blocks on the frame itself, so the tests wait for precisely as long as the send takes and
 * the {@link Timeout} is what turns a stream that never delivers into a failure rather than a
 * hung build.
 *
 * <p>Subscriber counts are asserted as differences rather than as totals: one in-memory
 * application is shared by every test in the suite, and a total would be a claim about what
 * some other class left behind.
 */
class OneStreamCarriesEveryBrowserItsHintsTest extends BootedApplicationTest {

	private static final long TRANSFER = 7L;

	private static final Duration LONG_ENOUGH_FOR_A_CLOSED_SOCKET_TO_SHOW = Duration.ofSeconds(5);

	@LocalServerPort
	private int port;

	@Autowired
	private TransferEventStream stream;

	/**
	 * The header {@code EventSource} refuses to open a connection without, checked here
	 * because nothing else in this suite can: the frontend's own tests replace
	 * {@code EventSource} with a fake (ticket 37), so this is the only place the real content
	 * negotiation is exercised.
	 */
	@Test
	@Timeout(10)
	@DisplayName("the stream answers as an event stream, on the public API prefix")
	void answersAsAnEventStream() throws Exception {
		try (SubscribedBrowser browser = SubscribedBrowser.subscribeTo(port)) {
			assertThat(browser.status()).isEqualTo(200);
			assertThat(browser.contentType()).startsWith("text/event-stream");
		}
	}

	/**
	 * The message shape ticket 36 wrote the frontend against, asserted as the literal text on
	 * the wire rather than through a parser.
	 *
	 * <p>Comparing parsed objects would prove this application agrees with itself. What has to
	 * hold is narrower and is not in this repository's compiler's reach: a deployed
	 * {@code events.ts} reads a JSON object with a {@code type} it recognises and a
	 * {@code transferId}, on the default event, and ignores everything else — so a renamed
	 * member or a named SSE event is a live-update feature that silently does nothing. The
	 * string is the contract.
	 */
	@Test
	@Timeout(10)
	@DisplayName("a message carries an event type and a Transfer ID, and nothing else")
	void carriesOnlyAnEventTypeAndATransferId() throws Exception {
		try (SubscribedBrowser browser = SubscribedBrowser.subscribeTo(port)) {
			stream.broadcast(new TransferEvent(TRANSFER_SETTLED, TRANSFER));

			assertThat(browser.nextMessage()).isEqualTo(frameFor("TRANSFER_SETTLED", TRANSFER));
		}
	}

	/**
	 * One endpoint serving every subscriber is design decision 17's choice, and this is the
	 * property that makes it a choice rather than a shortcut: a browser is not registered
	 * against a Transfer, so it hears about all of them and decides for itself.
	 */
	@Test
	@Timeout(10)
	@DisplayName("every subscriber receives every message, from the one endpoint")
	void servesEverySubscriberFromOneEndpoint() throws Exception {
		try (SubscribedBrowser first = SubscribedBrowser.subscribeTo(port);
				SubscribedBrowser second = SubscribedBrowser.subscribeTo(port)) {

			stream.broadcast(new TransferEvent(TRANSFER_REJECTED, TRANSFER));
			stream.broadcast(new TransferEvent(TRANSFER_EXPIRED, TRANSFER + 1));

			assertThat(first.nextMessage()).isEqualTo(frameFor("TRANSFER_REJECTED", TRANSFER));
			assertThat(first.nextMessage()).isEqualTo(frameFor("TRANSFER_EXPIRED", TRANSFER + 1));
			assertThat(second.nextMessage()).isEqualTo(frameFor("TRANSFER_REJECTED", TRANSFER));
			assertThat(second.nextMessage()).isEqualTo(frameFor("TRANSFER_EXPIRED", TRANSFER + 1));
		}
	}

	/**
	 * The leak the ticket names, and the only one of these assertions that cannot be made from
	 * outside: a departed browser leaves nothing an HTTP client can observe, so the registry's
	 * own count is what says whether it is still holding an emitter.
	 *
	 * <p><b>The departure is noticed on a write, which is why the broadcast is inside the
	 * wait.</b> Closing the socket does not, on its own and within any interval this test could
	 * name, make the container run the emitter's callbacks: the response is an open async one
	 * that nothing is reading from, so the server learns the client is gone by trying to use it
	 * — and even then the first attempt after the close can still succeed into a kernel buffer.
	 * Waiting without sending was measured here as waiting for something that never happens.
	 * That is also why there is no second, quieter assertion for a browser that closes politely:
	 * this suite has one way to leave, and a departure is a departure.
	 *
	 * <p>The surviving browser is asserted last, and it is half the point: dropping a
	 * subscriber must not be something that happens by an exception escaping the broadcast, or
	 * one closed tab would cost every other operator their live updates.
	 */
	@Test
	@Timeout(30)
	@DisplayName("a browser that goes away is dropped, and the ones that stayed keep their hints")
	void dropsABrowserThatWentAwayWithoutCostingTheOthers() throws Exception {
		int registeredBefore = stream.subscriberCount();

		try (SubscribedBrowser staying = SubscribedBrowser.subscribeTo(port)) {
			SubscribedBrowser leaving = SubscribedBrowser.subscribeTo(port);
			assertThat(stream.subscriberCount())
					.as("a subscription is registered by the time the response begins")
					.isEqualTo(registeredBefore + 2);

			leaving.close();

			await().atMost(LONG_ENOUGH_FOR_A_CLOSED_SOCKET_TO_SHOW).untilAsserted(() -> {
				stream.broadcast(new TransferEvent(TRANSFER_SETTLED, TRANSFER));
				assertThat(stream.subscriberCount()).isEqualTo(registeredBefore + 1);
			});

			assertThat(staying.nextMessage()).isEqualTo(frameFor("TRANSFER_SETTLED", TRANSFER));
		}
	}
}
