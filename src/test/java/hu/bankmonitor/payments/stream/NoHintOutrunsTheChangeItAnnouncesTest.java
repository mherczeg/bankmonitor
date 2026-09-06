package hu.bankmonitor.payments.stream;

import hu.bankmonitor.testsupport.BootedApplicationTest;
import hu.bankmonitor.testsupport.SubscribedBrowser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static hu.bankmonitor.payments.stream.TransferEventType.TRANSFER_REJECTED;
import static hu.bankmonitor.payments.stream.TransferEventType.TRANSFER_SETTLED;
import static hu.bankmonitor.testsupport.SubscribedBrowser.frameFor;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ordering the whole live-update story rests on: a hint is sent only once the change it
 * describes has committed.
 *
 * <p>It is the one property of this slice that no assertion about a message's <em>content</em>
 * can reach. A browser answers a hint by refetching, and design decision 17 gives this stream
 * no replay, no buffer and no {@code Last-Event-ID} — so a hint that overtook its own commit
 * would send the browser to read the row as it was before, and nothing would ever tell it
 * again. The page would be permanently wrong with nothing in a log to say so, which is a
 * failure worth a test of its own.
 *
 * <p>Announcements are published here directly rather than by settling a Transfer, because
 * what is under test is the delivery and not the caller: a rolled-back settlement is awkward
 * to arrange and would prove the same thing less clearly. The end-to-end direction — that
 * recording the Verdicts really does reach a browser — is
 * {@code AFinishedTransferTellsEveryBrowserTest} in {@code transfers}.
 */
class NoHintOutrunsTheChangeItAnnouncesTest extends BootedApplicationTest {

	private static final long ABANDONED = 7L;

	private static final long COMMITTED = 8L;

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationEventPublisher announcements;

	@Autowired
	private PlatformTransactionManager transactions;

	/**
	 * Two announcements, one from a transaction that is abandoned and one from a transaction
	 * that commits, and the assertion is that the <em>first</em> message the browser reads is
	 * the second announcement.
	 *
	 * <p><b>Phrased as a positive assertion deliberately.</b> The claim is that something was
	 * never sent, and the direct form of that — read for a while, assert nothing arrived — is
	 * a sleep whose length is either a slow test or a lie. Reading one message and finding the
	 * committed one is the same claim with no interval in it: the abandoned hint was published
	 * first and had every opportunity to be first on the wire.
	 *
	 * <p>What it proves is that the listener is bound to the commit rather than to the
	 * publish. A plain {@code @EventListener} in place of the
	 * {@link org.springframework.transaction.event.TransactionalEventListener} fails it on the
	 * first line of the assertion. What it does not prove is anything about a transaction that
	 * commits and then fails afterwards, or about two commits racing: the first is not a state
	 * this application can reach, and the second is design decision 17's explicit
	 * non-requirement — a hint carries no order because it carries no content.
	 */
	@Test
	@Timeout(10)
	@DisplayName("a hint announced by a transaction that was abandoned is never sent")
	void sendsNoHintForAChangeThatNeverCommitted() throws Exception {
		TransactionTemplate transaction = new TransactionTemplate(transactions);

		try (SubscribedBrowser browser = SubscribedBrowser.subscribeTo(port)) {
			transaction.executeWithoutResult(abandoned -> {
				announcements.publishEvent(new TransferEvent(TRANSFER_REJECTED, ABANDONED));
				abandoned.setRollbackOnly();
			});

			transaction.executeWithoutResult(committing ->
					announcements.publishEvent(new TransferEvent(TRANSFER_SETTLED, COMMITTED)));

			assertThat(browser.nextMessage())
					.as("the abandoned announcement was published first and must not be on the wire")
					.isEqualTo(frameFor("TRANSFER_SETTLED", COMMITTED));
		}
	}
}
