package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.common.ProblemType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The problem documents more than one endpoint in this slice answers with.
 *
 * <p>It exists because {@link UnknownTransferException} is now raised on two paths — a
 * Transfer fetched by a client and a Verdict reported by a Check service — and what status
 * that fact earns is one decision, not two. Two controllers each writing their own
 * {@code 404} would be two copies of the rule, and the copy that was not edited would be the
 * one a client had trusted.
 *
 * <p>What is <em>not</em> here is every other refusal: those belong to the one endpoint that
 * can meet them, on the controller that raises them, where design decision 14 put them for
 * ordering reasons that have not changed. This class is a place for the shared ones and not
 * a layer between a controller and its answers.
 */
final class TransferProblems {

	private static final String UNKNOWN_TRANSFER_DETAIL = "No Transfer has that identifier.";

	private TransferProblems() {
	}

	/**
	 * The {@code 404} ticket 05 settled, meaning <em>the path names nothing</em>. A Transfer's
	 * identifier is its own address on both endpoints that raise this, so an identifier no
	 * Transfer has addresses no resource — which is a different fact from ticket 14's
	 * {@code 422} for an unknown Account named <em>inside</em> a payload.
	 */
	static ProblemDetail unknownTransfer(UnknownTransferException refusal) {
		ProblemDetail problem = of(HttpStatus.NOT_FOUND, ProblemType.NOT_FOUND, UNKNOWN_TRANSFER_DETAIL);
		problem.setProperty("transferId", refusal.getTransferId());
		return problem;
	}

	/**
	 * The parts every problem document in this slice shares. {@code instance} is left unset on
	 * purpose: the message converter fills it in from the request URI for any
	 * {@link ProblemDetail} that does not name one, so setting it here would be a second copy
	 * of the request path to keep right.
	 *
	 * <p>Every {@code detail} is written by the caller rather than taken from the exception,
	 * on {@code ProblemDocumentAdvice}'s reasoning: an exception message is written for a log.
	 */
	static ProblemDetail of(HttpStatus status, ProblemType type, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(type.uri());
		return problem;
	}
}
