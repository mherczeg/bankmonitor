package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.accounts.InsufficientFundsException;
import hu.bankmonitor.payments.accounts.UnknownAccountException;
import hu.bankmonitor.payments.common.Money;
import hu.bankmonitor.payments.common.ProblemType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * The Transfers resource, under the {@code /api} prefix the security chain opens by name.
 *
 * <p>It holds no repository — the boundary {@code ModuleBoundariesHoldTest} enforces — and
 * no rule of its own: what a request may say is declared as constraints on
 * {@link CreateTransferRequest}, and every refusal a Transfer can meet is raised where the
 * facts to decide it are, under the lock. What this class adds is the last step, below:
 * which status code each of those refusals is worth.
 *
 * <p><b>The refusal handlers sit on the controller rather than in a {@code
 * @ControllerAdvice}</b>, where they are reached by the structure of the resolver's lookup.
 * An advice would instead have to out-order {@code ProblemDocumentAdvice}, whose handler for
 * {@code Exception} would otherwise answer every refusal below as a {@code 500} (design
 * decision 14).
 *
 * <p>The path is written out here and again in the tests that call it, rather than shared
 * through a constant, on {@code AccountController}'s reasoning: a renamed path is a
 * breaking change for the generated frontend types, and a test reading the same constant as
 * the mapping would follow the rename silently instead of failing on it.
 */
@RestController
@RequestMapping("/api/transfers")
class TransferController {

	private static final String SELF_TRANSFER_DETAIL =
			"A Transfer must move money between two different Accounts.";

	private static final String UNKNOWN_ACCOUNT_DETAIL = "No Account has that identifier.";

	private static final String INSUFFICIENT_FUNDS_DETAIL =
			"The source Account's Available Balance does not cover this Transfer.";

	private static final String CROSS_CURRENCY_DETAIL =
			"This service cannot yet convert between the two Accounts' Currencies.";

	private final FundsReservation reservation;

	private final Clock clock;

	TransferController(FundsReservation reservation, Clock clock) {
		this.reservation = reservation;
		this.clock = clock;
	}

	/**
	 * Answers {@code 201} with the newly {@code PENDING} Transfer, its funds already
	 * reserved on the source Account and no money moved.
	 *
	 * <p>No {@code Location} header, on {@code AccountController}'s reasoning inverted:
	 * ticket 15 gives a Transfer a resource of its own, and the header belongs with it
	 * rather than ahead of it.
	 *
	 * <p><b>The Idempotency Key is required here and used nowhere</b>, which is deliberate
	 * rather than unfinished. The guarantee behind the key — that a retry is
	 * indistinguishable from a first call — is ticket 17's, and it needs the record and the
	 * claim mechanics of ticket 16 underneath it. What cannot wait is the <em>contract</em>:
	 * a version of this endpoint that let a client omit the key is a version clients get
	 * written against, and those are the clients still in production when the guarantee
	 * arrives.
	 *
	 * <p>{@code required = false} with {@code @NotNull} rather than a required header,
	 * because the two say the same thing to a caller and only one of them says it in the
	 * shape every other refusal uses: a missing header taken as missing produces Spring's
	 * own {@code MissingRequestHeaderException} and a problem document with no {@code
	 * errors} entry, where a null one that fails a constraint produces the same document a
	 * rejected field does.
	 *
	 * <p><b>{@code @UUID} on its defaults accepts less than its name</b>: it refuses a
	 * UUIDv7 and any uppercase key, both of which are well-formed. The members are
	 * therefore spelled out, and there is no value meaning <em>any</em> version (design
	 * decision 14). {@code allowNil} is off deliberately — the all-zero UUID is the one
	 * key a caller can guess, and these keys are globally scoped.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	TransferResponse requestTransfer(
			@RequestHeader(name = "X-Idempotency-Key", required = false) @NotNull
			@UUID(version = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
					letterCase = UUID.LetterCase.INSENSITIVE, allowNil = false) String idempotencyKey,
			@Valid @RequestBody CreateTransferRequest request) {
		return TransferResponse.of(reservation.reserve(request.reservationAt(clock.instant())));
	}

	/**
	 * Every refusal below is {@code 422} rather than {@code 400}. Each is decided against
	 * Accounts the request could not see, so the request was well-formed and this API
	 * understood it — which is exactly the distinction the two statuses draw. A {@code 404}
	 * for an unknown Account was rejected for a sharper reason: this API answers {@code 404}
	 * when the <em>path</em> names nothing, and {@code /api/transfers} always exists.
	 */
	@ExceptionHandler
	ProblemDetail handleSelfTransfer(SelfTransferNotAllowedException refusal) {
		ProblemDetail problem = refusalOf(ProblemType.SELF_TRANSFER, SELF_TRANSFER_DETAIL);
		problem.setProperty("accountId", refusal.getAccountId());
		return problem;
	}

	@ExceptionHandler
	ProblemDetail handleUnknownAccount(UnknownAccountException refusal) {
		ProblemDetail problem = refusalOf(ProblemType.UNKNOWN_ACCOUNT, UNKNOWN_ACCOUNT_DETAIL);
		problem.setProperty("accountId", refusal.getAccountId());
		return problem;
	}

	/**
	 * Both figures that were compared, and the Currency they were compared in — which is one
	 * member rather than two because an Available Balance and the amount tested against it
	 * cannot be denominated differently and still have been compared.
	 */
	@ExceptionHandler
	ProblemDetail handleInsufficientFunds(InsufficientFundsException refusal) {
		Money availableBalance = refusal.getAvailableBalance();
		ProblemDetail problem = refusalOf(ProblemType.INSUFFICIENT_FUNDS, INSUFFICIENT_FUNDS_DETAIL);
		problem.setProperty("availableBalanceMinorUnits", availableBalance.minorUnits());
		problem.setProperty("requestedAmountMinorUnits", refusal.getRequestedAmount().minorUnits());
		problem.setProperty("currency", availableBalance.currency());
		return problem;
	}

	@ExceptionHandler
	ProblemDetail handleCrossCurrencyTransfer(CrossCurrencyTransferNotSupportedException refusal) {
		ProblemDetail problem = refusalOf(ProblemType.CROSS_CURRENCY_UNSUPPORTED, CROSS_CURRENCY_DETAIL);
		problem.setProperty("sourceCurrency", refusal.getSourceCurrency());
		problem.setProperty("destinationCurrency", refusal.getDestinationCurrency());
		return problem;
	}

	/**
	 * The parts every refusal shares. {@code instance} is left unset on purpose: the message
	 * converter fills it in from the request URI for any {@code ProblemDetail} that does not
	 * name one, so setting it here would be a second copy of the request path to keep right.
	 *
	 * <p>Every {@code detail} is written here rather than taken from the exception, on
	 * {@code ProblemDocumentAdvice}'s reasoning: an exception message is written for a log,
	 * and two of these carry {@code Money}, whose {@code toString} is a record's.
	 */
	private static ProblemDetail refusalOf(ProblemType type, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, detail);
		problem.setType(type.uri());
		return problem;
	}
}
