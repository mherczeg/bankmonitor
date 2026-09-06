package hu.bankmonitor.payments.stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every browser currently listening, and the one thing this application says to all of them.
 *
 * <p>One registry rather than one per Transfer, which is design decision 17's choice: a
 * single list plus a client-side "not my Transfer, ignore it" is less machinery than
 * subscriptions keyed by Transfer ID, each with a lifecycle of its own. What it costs is that
 * every subscriber sees every Transfer's hints — a consequence of having no identity to scope
 * by rather than a decision of its own, recorded in {@code docs/deferred.md}.
 *
 * <p><b>A hint is sent after the change it describes has committed, and that ordering is the
 * whole correctness argument.</b> The stream carries no state, so a browser that is told to
 * refetch reads whatever the database will show it; told too early, it reads the old row and
 * — since design decision 17 has no catch-up, no replay and no {@code Last-Event-ID} — it is
 * never told again. The page would then be permanently wrong with nothing in a log to say so.
 * {@link TransactionalEventListener} at {@link TransactionPhase#AFTER_COMMIT} is what makes
 * "after" structural instead of a comment: a caller in {@code transfers} publishes an ordinary
 * application event from inside its own transaction, and the send happens once that
 * transaction has committed — so a settlement that rolls back sends nothing.
 *
 * <p>It is also what keeps this class out of the locked path. A settlement holds row locks on
 * a Transfer and two Accounts, and {@code LockedPathTouchesOnlyTheDatabaseTest} forbids
 * anything on that path from reaching the servlet or web layers at all — which is exactly
 * right, because writing to an arbitrary number of possibly-slow browser sockets is the
 * clearest example there is of a lock waiting on something slower than the database. Nothing
 * in {@code transfers} names this class; the container joins the two halves.
 *
 * <p>{@code fallbackExecution} is on so that a caller with no transaction open gets the hint
 * immediately rather than silently getting nothing. The ordering property survives: with no
 * transaction pending, there is no later truth for the hint to overtake.
 */
@Component
class TransferEventStream {

	/**
	 * The SSE comment written on subscription. Its text is never read by anything — see
	 * {@link #open()} for what sending it is for.
	 */
	private static final String STREAM_OPEN = "stream open";

	private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

	private final Duration connectionTimeout;

	TransferEventStream(@Value("${payments.stream.connection-timeout}") Duration connectionTimeout) {
		this.connectionTimeout = connectionTimeout;
	}

	/**
	 * Registers one browser, puts the response on the wire, and hands it back.
	 *
	 * <p>No state is sent on subscription and nothing is replayed. The client's own first fetch
	 * is what converges it from any state it missed, which is what makes a dropped connection
	 * free and is why this endpoint needs neither a buffer nor a cursor.
	 *
	 * <p><b>The comment that is sent is not a message; it is what opens the stream.</b>
	 * Spring hands the container an emitter and writes nothing of its own, so until something
	 * is sent the response sits unflushed in Tomcat's buffer: the client has had no status
	 * line, no {@code Content-Type} and therefore no stream to be subscribed to. A comment is
	 * the SSE specification's own no-op — a line beginning with {@code :} that
	 * {@code EventSource} discards without dispatching anything — so it costs the browser
	 * nothing and cannot be mistaken for a hint. What it buys is that a browser's
	 * {@code onopen} fires when it subscribes rather than whenever some unrelated Transfer
	 * next finishes, and the refetch a browser does on {@code onopen} is the whole reason this
	 * stream can afford to carry no catch-up.
	 */
	SseEmitter open() {
		SseEmitter subscriber = new SseEmitter(connectionTimeout.toMillis());
		subscriber.onCompletion(() -> subscribers.remove(subscriber));
		subscriber.onTimeout(() -> subscribers.remove(subscriber));
		subscriber.onError(failure -> subscribers.remove(subscriber));
		subscribers.add(subscriber);
		send(subscriber, SseEmitter.event().comment(STREAM_OPEN));
		return subscriber;
	}

	/**
	 * Tells every listening browser that one Transfer has finished, once the change that
	 * finished it is committed and visible to the refetch this provokes.
	 *
	 * <p>Public on a package-private class for the reason a {@code @Transactional} method is:
	 * a listener the container finds by reflection is not the place to discover that something
	 * quietly did not register.
	 *
	 * <p>A frame is built inside the loop rather than once outside it because
	 * {@code SseEmitter.event()} returns a builder that the send consuming it empties. Shared,
	 * the first browser would get the hint and every other one would get an empty write.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void broadcast(TransferEvent event) {
		subscribers.forEach(subscriber ->
				send(subscriber, SseEmitter.event().data(event, MediaType.APPLICATION_JSON)));
	}

	/** How many browsers are registered — the state the disconnection test reads. */
	int subscriberCount() {
		return subscribers.size();
	}

	/**
	 * A browser that cannot be written to is dropped rather than retried or reported.
	 *
	 * <p>It is the ordinary end of a subscription and not an error: a closed tab is discovered
	 * on the next write, and often only there, because a client that vanishes without closing
	 * the socket leaves nothing for the container's callbacks to fire on. Every failure is
	 * caught, not only {@link java.io.IOException} — sending to an emitter the container has
	 * already completed throws {@link IllegalStateException} — because one departed browser
	 * must not cost the others their hint.
	 */
	private void send(SseEmitter subscriber, SseEmitter.SseEventBuilder frame) {
		try {
			subscriber.send(frame);
		}
		catch (Exception unreachableBrowser) {
			subscribers.remove(subscriber);
			release(subscriber, unreachableBrowser);
		}
	}

	/**
	 * Ends a subscription the container may have ended already.
	 *
	 * <p>Guarded for the same reason the send is, one line further along. The removal above is
	 * what actually drops the browser; this call only tells the container, and a container that
	 * has already run {@code AsyncListener.onError} on that async context refuses it by
	 * throwing {@link IllegalStateException}. Both are ways of discovering the same departure
	 * and either can get there first, so an unguarded call here would let one closed tab throw
	 * out of {@code broadcast} and cost every browser behind it in the loop its hint — the
	 * exact failure the guard around the send exists to prevent.
	 */
	private static void release(SseEmitter subscriber, Exception unreachableBrowser) {
		try {
			subscriber.completeWithError(unreachableBrowser);
		}
		catch (Exception alreadyEnded) {
			// the container got there first, and there is nothing left to tell it
		}
	}
}
