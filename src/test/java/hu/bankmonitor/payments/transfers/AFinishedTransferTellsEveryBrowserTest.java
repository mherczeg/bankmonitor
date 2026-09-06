package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.testsupport.SubscribedBrowser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static hu.bankmonitor.payments.common.Currency.EUR;
import static hu.bankmonitor.payments.transfers.checks.Check.FRAUD;
import static hu.bankmonitor.payments.transfers.checks.Check.MANUAL_APPROVAL;
import static hu.bankmonitor.payments.transfers.checks.Verdict.APPROVED;
import static hu.bankmonitor.payments.transfers.checks.Verdict.REJECTED;
import static hu.bankmonitor.testsupport.SubscribedBrowser.frameFor;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The join between the two halves of the live-update story: a Transfer really finishing, and
 * a real browser on a real socket being told about it.
 *
 * <p>It lives in {@code transfers} and not beside the rest of the stream's tests because
 * {@link VerdictRecording} is package-private here — which is the arrangement rather than an
 * obstacle to work around. The two slices are joined by the container and by nothing else:
 * {@code transfers} publishes an application event, {@code stream} listens for one, and
 * neither package names a type of the other beyond the two value types a hint is made of.
 * That is what keeps a socket write off the path holding row locks on a Transfer and two
 * Accounts, which {@code LockedPathTouchesOnlyTheDatabaseTest} enforces. The cost of the
 * arrangement is that no compiler anywhere checks the two halves meet, so this is the test
 * that does.
 *
 * <p>The messages are compared as the literal text on the wire, on
 * {@code OneStreamCarriesEveryBrowserItsHintsTest}'s reasoning: the contract is with a
 * deployed {@code events.ts} that this repository's compiler cannot see.
 *
 * <p>Nothing here waits or polls. {@link VerdictRecording#recordVerdict} is transactional and
 * the hint is delivered as that transaction commits, so by the time the call returns the
 * message is already on the socket.
 */
class AFinishedTransferTellsEveryBrowserTest extends TransferScenario {

	private static final long SOURCE = 1L;

	private static final long DESTINATION = 2L;

	private static final long OPENING_BALANCE = 250_00L;

	private static final long AMOUNT = 80_00L;

	@LocalServerPort
	private int port;

	@Autowired
	private LifecycleHints hints;

	@Autowired
	private PlatformTransactionManager transactions;

	@BeforeEach
	void openBothAccounts() {
		openAccount(SOURCE, OPENING_BALANCE, EUR);
		openAccount(DESTINATION, 0L, EUR);
	}

	/**
	 * Settlement, end to end.
	 *
	 * <p>It also settles the question of what a Transfer that is still {@code PENDING} sends,
	 * without a second test and without asserting an absence: the first Verdict leaves the
	 * Transfer pending and is announced like any other, and the first message the browser reads
	 * is nevertheless the settlement. {@link LifecycleHints} mapping {@code PENDING} to nothing
	 * is what makes that true — an operator's own browser already knows it submitted the
	 * Transfer, and there is no one else the news would mean anything to.
	 */
	@Test
	@Timeout(30)
	@DisplayName("a settled Transfer is announced to a listening browser")
	void announcesASettlement() throws Exception {
		try (SubscribedBrowser browser = SubscribedBrowser.subscribeTo(port)) {
			long transfer = reserve();

			verdicts.recordVerdict(transfer, FRAUD, APPROVED);
			verdicts.recordVerdict(transfer, MANUAL_APPROVAL, APPROVED);

			assertThat(browser.nextMessage())
					.as("the pending Verdict before it announced nothing")
					.isEqualTo(frameFor("TRANSFER_SETTLED", transfer));
		}
	}

	/** Rejection, end to end. One Check saying no finishes the Transfer, so one message. */
	@Test
	@Timeout(30)
	@DisplayName("a rejected Transfer is announced to a listening browser")
	void announcesARejection() throws Exception {
		try (SubscribedBrowser browser = SubscribedBrowser.subscribeTo(port)) {
			long transfer = reserve();

			verdicts.recordVerdict(transfer, FRAUD, REJECTED);

			assertThat(browser.nextMessage())
					.isEqualTo(frameFor("TRANSFER_REJECTED", transfer));
		}
	}

	/**
	 * Expiry, announced through {@link LifecycleHints} rather than end to end, <b>because
	 * nothing in this application expires a Transfer yet</b>.
	 *
	 * <p>Ticket 23 is the reaper and is not built. Until it is, {@code EXPIRED} has no
	 * producer, and the honest thing is to say so here rather than to invent one for a test to
	 * drive: a fake reaper would assert that the fake works. What is genuinely testable is the
	 * half that exists — that the status maps to the hint the frontend was written against and
	 * that announcing it reaches a browser — and it is worth having, because the mapping is
	 * the piece a rename would break silently.
	 *
	 * <p>So ticket 23 inherits one obligation and no design: call
	 * {@link LifecycleHints#announce} with {@code EXPIRED} inside the transaction that releases
	 * the reservation. Everything from there is already built and covered by this test.
	 *
	 * <p><b>The announcement is made from inside a transaction that commits</b>, which is the
	 * arrangement ticket 23 is told to use and is therefore the one worth covering. Announcing
	 * with no transaction open would deliver too — {@code fallbackExecution} is on — but it
	 * would exercise the fallback rather than the after-commit path, and leave the only
	 * {@code EXPIRED} payload in the suite travelling a route no caller is meant to take.
	 */
	@Test
	@Timeout(30)
	@DisplayName("an expired Transfer is announced, though nothing expires one yet")
	void announcesAnExpiry() throws Exception {
		try (SubscribedBrowser browser = SubscribedBrowser.subscribeTo(port)) {
			long transfer = reserve();

			new TransactionTemplate(transactions)
					.executeWithoutResult(expiring -> hints.announce(transfer, TransferStatus.EXPIRED));

			assertThat(browser.nextMessage())
					.isEqualTo(frameFor("TRANSFER_EXPIRED", transfer));
		}
	}

	/** One Transfer in {@code PENDING}, through the scenario's own two phases. */
	private long reserve() {
		return reserve(transferOf(AMOUNT, SOURCE, DESTINATION)).getId();
	}
}
