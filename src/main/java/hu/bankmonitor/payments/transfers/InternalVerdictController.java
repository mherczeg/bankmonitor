package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.common.ProblemType;
import hu.bankmonitor.payments.transfers.checks.Check;
import hu.bankmonitor.payments.transfers.checks.CheckNotRequiredException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The inbound callback a Check service — or a human approver's back-office tool — reports a
 * Verdict through.
 *
 * <p><b>A thin adapter over {@link VerdictRecording#recordVerdict} and nothing else.</b> It
 * decides no part of a Transfer's lifecycle: the reading of the ledger, the money and the
 * status all happen under the row lock in one transaction the domain owns, and what is left
 * here is the translation between an HTTP request and that call. A broker consumer would be
 * a second adapter over the same operation, and this class is what it would resemble.
 *
 * <p><b>The asymmetry with outbound events is deliberate.</b> Outbound needs an outbox
 * because <em>we</em> own the atomicity of "the Transfer committed, therefore the event
 * exists". Inbound owns no such thing. It needs to be idempotent, and it already is: a
 * redelivered Verdict is absorbed by the ledger's guarded update, and one that arrives after
 * the Transfer finished meets {@link #handleLateVerdict} below.
 *
 * <p><b>What guards this endpoint is not in this file.</b> The shared secret is a rule about
 * the {@code /internal} prefix, enforced by the filter chain in {@code SecurityConfiguration}
 * before the dispatcher runs — so a second internal endpoint inherits it rather than having
 * to remember it, and no request that failed it can reach the code below. Design decision 10
 * has why the rule exists: an unauthenticated Verdict endpoint means anyone approves their
 * own Transfer and walks past fraud screening.
 *
 * <p>The refusal handlers sit on the controller rather than in a {@code @ControllerAdvice},
 * on {@code TransferController}'s reasoning: an advice would have to out-order
 * {@code ProblemDocumentAdvice}, whose handler for {@code Exception} would otherwise answer
 * each of them as a {@code 500}.
 */
@RestController
@RequestMapping(InternalVerdictController.PATH)
class InternalVerdictController {

	/**
	 * Under the prefix the security chain guards, which is what makes the mapping and the
	 * authorization rule agree by construction rather than by review.
	 */
	static final String PATH = "/internal/transfers";

	private static final String CHECK_NOT_REQUIRED_DETAIL =
			"That Transfer's Check Ledger has no such Check outstanding.";

	private static final String LATE_VERDICT_DETAIL =
			"That Transfer has already finished, so a Verdict no longer changes it.";

	private final VerdictRecording verdicts;

	InternalVerdictController(VerdictRecording verdicts) {
		this.verdicts = verdicts;
	}

	/**
	 * Records one Check's answer for one Transfer and answers with where the Transfer got to.
	 *
	 * <p>The Transfer and the Check are the address rather than the payload, so the URL names
	 * exactly the one outstanding ledger row being answered — design decision 9's shape,
	 * unchanged.
	 *
	 * <p><b>{@code 200} rather than {@code 201} or {@code 204}.</b> Nothing is created at a
	 * URL a caller could then fetch, and there is something to say: the status in
	 * {@link VerdictReceipt} is how an at-least-once reporter learns whether its Verdict was
	 * the last word. A {@code 204} would leave a Check service unable to tell "recorded, still
	 * waiting" from "recorded, and the money moved" without polling the Transfer back.
	 */
	@PostMapping("/{transferId}/checks/{check}")
	VerdictReceipt reportVerdict(@PathVariable long transferId, @PathVariable Check check,
			@Valid @RequestBody VerdictReport report) {
		return new VerdictReceipt(transferId, check, report.verdict(),
				verdicts.recordVerdict(transferId, check, report.verdict()));
	}

	/** Shared with {@code TransferController}, because it is one rule about one fact. */
	@ExceptionHandler
	ProblemDetail handleUnknownTransfer(UnknownTransferException refusal) {
		return TransferProblems.unknownTransfer(refusal);
	}

	/**
	 * A Check the Transfer's ledger has no row for: a reporting service running against stale
	 * configuration, or the right Verdict sent to the wrong Transfer. The same {@code 404} as
	 * an unknown Transfer, because the pair <em>is</em> the path and this pair addresses
	 * nothing — but under a URN of its own, because design decision 18 makes the URN the only
	 * member a client may branch on. Sharing {@code not-found} would leave the two told apart
	 * by which properties happened to be present, which is branching on something else.
	 *
	 * <p>Both halves are on the wire because neither is recoverable from the other, and the
	 * useful answer to "why was this refused" names the pair.
	 */
	@ExceptionHandler
	ProblemDetail handleCheckNotRequired(CheckNotRequiredException refusal) {
		ProblemDetail problem = TransferProblems.of(
				HttpStatus.NOT_FOUND, ProblemType.CHECK_NOT_REQUIRED, CHECK_NOT_REQUIRED_DETAIL);
		problem.setProperty("transferId", refusal.getTransferId());
		problem.setProperty("check", refusal.getCheck());
		return problem;
	}

	/**
	 * A Verdict that arrived after the Transfer finished. {@code 409}, because the request is
	 * well-formed and understood and it is the Transfer's state that refuses it — the same
	 * reading of the status the two idempotency conflicts use, and the reason this needs a URN
	 * of its own to be told apart from them.
	 *
	 * <p><b>It carries the status the Transfer had already reached</b>, which is what turns a
	 * refusal into news: a Check service redelivering the Verdict that settled a Transfer
	 * learns its report landed, and one answering a Transfer the reaper expired learns it did
	 * not.
	 *
	 * <p>No {@code Retry-After}, and its absence is the machine-readable half of the refusal
	 * on {@code TransferController}'s precedent: a Transfer never leaves a terminal status, so
	 * a caller that retried this would retry for ever.
	 */
	@ExceptionHandler
	ProblemDetail handleLateVerdict(TransferNotPendingException refusal) {
		ProblemDetail problem = TransferProblems.of(
				HttpStatus.CONFLICT, ProblemType.TRANSFER_NOT_PENDING, LATE_VERDICT_DETAIL);
		problem.setProperty("transferId", refusal.getTransferId());
		problem.setProperty("transferStatus", refusal.getStatus());
		return problem;
	}
}
