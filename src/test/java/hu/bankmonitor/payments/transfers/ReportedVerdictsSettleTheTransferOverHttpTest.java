package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.common.ProblemType;
import hu.bankmonitor.payments.transfers.checks.Check;
import hu.bankmonitor.payments.transfers.checks.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static hu.bankmonitor.payments.common.Currency.EUR;
import static hu.bankmonitor.payments.transfers.checks.Check.FRAUD;
import static hu.bankmonitor.payments.transfers.checks.Check.MANUAL_APPROVAL;
import static hu.bankmonitor.payments.transfers.checks.Verdict.APPROVED;
import static hu.bankmonitor.payments.transfers.checks.Verdict.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The endpoint against the running application and a real database: two Verdicts posted over
 * HTTP move the money, and the same two posted without the secret move nothing.
 *
 * <p>{@link RecordedVerdictsAdvanceTheTransferTest} makes the same claims about
 * {@link VerdictRecording}, and this class exists because the adapter in front of it is the
 * half that can be wrong on its own: a controller that mapped the Check onto the wrong path
 * segment, or a filter chain that let an unauthenticated caller through, would leave every
 * assertion in that class green. Both balances are read back in SQL for its reason — the
 * arithmetic and the write are two different places.
 *
 * <p>This is also where the secret rule is worth asserting twice. The web slice test proves
 * the chain refuses; this proves the refusal happened before anything was written, which is
 * the property the rule exists for and the one a caller cannot observe from the response.
 */
class ReportedVerdictsSettleTheTransferOverHttpTest extends TransferScenario {

	private static final long SOURCE = 1L;
	private static final long DESTINATION = 2L;
	private static final long OPENING_BALANCE = 250_00L;
	private static final long AMOUNT = 80_00L;

	private static final String SECRET_HEADER = "X-Internal-Secret";

	@Value("${payments.internal.shared-secret}")
	private String secret;

	@BeforeEach
	void openBothAccounts() {
		openAccount(SOURCE, OPENING_BALANCE, EUR);
		openAccount(DESTINATION, 0L, EUR);
	}

	@Test
	@DisplayName("both Checks approving over HTTP settles the Transfer and moves the money")
	void bothChecksApprovingOverHttpSettlesTheTransfer() {
		long transfer = reserve();

		assertThat(reporting(transfer, FRAUD, APPROVED)).isEqualTo(TransferStatus.PENDING);
		assertThat(reporting(transfer, MANUAL_APPROVAL, APPROVED)).isEqualTo(TransferStatus.SETTLED);

		assertThat(statusOf(transfer)).isEqualTo("SETTLED");
		assertThat(balanceOf(SOURCE)).isEqualTo(OPENING_BALANCE - AMOUNT);
		assertThat(reservedAmountOf(SOURCE)).as("the reservation was consumed, not left behind").isZero();
		assertThat(balanceOf(DESTINATION)).isEqualTo(AMOUNT);
	}

	/**
	 * The rejection path over the same endpoint, because it is the one that has to move no
	 * money: a human approver declining in a back-office tool and a fraud service saying no
	 * are the same call.
	 */
	@Test
	@DisplayName("one Check rejecting over HTTP releases the reservation and moves nothing")
	void oneCheckRejectingOverHttpReleasesTheReservation() {
		long transfer = reserve();

		assertThat(reporting(transfer, FRAUD, REJECTED)).isEqualTo(TransferStatus.REJECTED);

		assertThat(statusOf(transfer)).isEqualTo("REJECTED");
		assertThat(balanceOf(SOURCE)).isEqualTo(OPENING_BALANCE);
		assertThat(reservedAmountOf(SOURCE)).isZero();
		assertThat(balanceOf(DESTINATION)).isZero();
	}

	/**
	 * The rule this ticket exists for, stated as the thing that would have happened without
	 * it: an unauthenticated caller approving its own Transfer walks straight past fraud
	 * screening. Nothing is written — the ledger row is still outstanding and the Transfer is
	 * still {@code PENDING} — which is what "refused before the domain operation is reached"
	 * means and what the response status alone cannot show.
	 */
	@Test
	@DisplayName("a Verdict reported without the secret writes nothing at all")
	void aVerdictWithoutTheSecretWritesNothing() {
		long transfer = reserve();

		client().post().uri(path(transfer, FRAUD))
				.contentType(MediaType.APPLICATION_JSON)
				.body(approving(APPROVED))
				.exchange()
				.expectStatus().isForbidden()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.type").isEqualTo(ProblemType.FORBIDDEN.urn());

		assertThat(statusOf(transfer)).isEqualTo("PENDING");
		assertThat(verdictOn(transfer, FRAUD)).as("the ledger row is still outstanding").isNull();
		assertThat(reservedAmountOf(SOURCE)).isEqualTo(AMOUNT);
	}

	/**
	 * A denial issues no session cookie, which is not the same claim as
	 * {@code SecurityChainTest}'s: that one is made about a request the chain permits, and the
	 * denial path is the one that runs Spring Security's entry point, where a request cache
	 * would create the session the stateless policy says does not exist.
	 */
	@Test
	@DisplayName("a denial is stateless too")
	void aDenialIssuesNoSessionCookie() {
		client().post().uri(path(reserve(), FRAUD))
				.contentType(MediaType.APPLICATION_JSON)
				.body(approving(APPROVED))
				.exchange()
				.expectStatus().isForbidden()
				.expectHeader().doesNotExist("Set-Cookie");
	}

	/**
	 * Posts one Verdict and returns where the Transfer got to, having first asserted that the
	 * receipt echoes the report it is a receipt for — which is the half of the mapping a
	 * status alone would not catch, since a controller that read the wrong path segment would
	 * still answer {@code PENDING} at the right moment.
	 */
	private TransferStatus reporting(long transferId, Check check, Verdict verdict) {
		VerdictReceipt receipt = client().post().uri(path(transferId, check))
				.header(SECRET_HEADER, secret)
				.contentType(MediaType.APPLICATION_JSON)
				.body(approving(verdict))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.OK)
				.expectBody(VerdictReceipt.class)
				.returnResult()
				.getResponseBody();

		assertThat(receipt).isNotNull();
		assertThat(receipt.transferId()).isEqualTo(transferId);
		assertThat(receipt.check()).isEqualTo(check);
		assertThat(receipt.verdict()).isEqualTo(verdict);
		return receipt.transferStatus();
	}

	private long reserve() {
		return reservation.reserve(transferOf(AMOUNT, SOURCE, DESTINATION)).getId();
	}

	private static String approving(Verdict verdict) {
		return """
				{"verdict": "%s"}""".formatted(verdict);
	}

	private static String path(long transferId, Check check) {
		return "/internal/transfers/%d/checks/%s".formatted(transferId, check);
	}
}
