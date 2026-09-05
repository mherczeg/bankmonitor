package hu.bankmonitor.testsupport;

import hu.bankmonitor.payments.common.ProblemType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The failures the error contract is asserted against: a body and a parameter that fail
 * validation, a request that fails inside the handler, and the two refusals a slice raises
 * itself.
 *
 * <p>Deliberately outside {@code hu.bankmonitor.payments} so component scan never picks
 * it up on its own — see {@link ThreadProbeController} for why that matters — and under
 * {@code /api} so the security chain permits it.
 *
 * <p>It stands in for endpoints that do not exist yet. The contract it exercises is the
 * one every later slice inherits, and testing it against a real controller now is what
 * keeps the shape from being asserted against a mock of itself.
 */
@RestController
@RequestMapping(ProblemProbeController.PATH)
public class ProblemProbeController {

	public static final String PATH = "/api/test-probe/problems";

	public static final String RETRYABLE_REFUSAL = "/retryable-refusal";

	public static final String PERMANENT_REFUSAL = "/permanent-refusal";

	public static final String HANDLER_FAILURE = "/handler-failure";

	public static final String CONSTRAINED_PARAMETER = "/constrained-parameter";

	/** Whatever a 500's detail says, it must not be this. */
	public static final String LEAKED_INTERNAL_DETAIL = "jdbc:h2:mem:payments, table ACCOUNT";

	public static final String RETRY_AFTER_SECONDS = "1";

	@PostMapping
	void accept(@Valid @RequestBody Request request) {
	}

	/**
	 * A constraint on the method's own parameter rather than on a body, which Spring
	 * reports through a different exception and which the contract has to answer with the
	 * same document. Ticket 16's idempotency key header is the real instance of this.
	 */
	@GetMapping(CONSTRAINED_PARAMETER)
	void requireAPositivePageSize(@RequestParam @Positive int pageSize) {
	}

	@GetMapping(HANDLER_FAILURE)
	void failInsideTheHandler() {
		throw new IllegalStateException(LEAKED_INTERNAL_DETAIL);
	}

	/**
	 * A refusal that already names its own problem type and says when to come back —
	 * the shape design decision 18 gives the in-progress {@code 409} and the exhausted
	 * FX provider's {@code 503}.
	 */
	@GetMapping(RETRYABLE_REFUSAL)
	void refuseForNow() {
		ErrorResponseException refusal = refusal(ProblemType.REQUEST_IN_PROGRESS,
				"The same idempotency key is already being processed.");
		refusal.getHeaders().add(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
		throw refusal;
	}

	/** The same status under a different type URN, where retrying can never succeed. */
	@GetMapping(PERMANENT_REFUSAL)
	void refuseForGood() {
		throw refusal(ProblemType.IDEMPOTENCY_KEY_REUSED,
				"This idempotency key was already used for a different request.");
	}

	private static ErrorResponseException refusal(ProblemType type, String detail) {
		ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
		body.setType(type.uri());
		return new ErrorResponseException(HttpStatus.CONFLICT, body, null);
	}

	/**
	 * Two constraints on two properties, and nothing from the domain: an amount without
	 * a currency is not a concept this codebase has (see {@code CONTEXT.md}), and a
	 * fixture is the last place to introduce one.
	 */
	public record Request(@NotBlank String reference, @Positive Integer weight) {
	}
}
