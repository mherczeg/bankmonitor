package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.accounts.InsufficientFundsException;
import hu.bankmonitor.payments.accounts.UnknownAccountException;
import hu.bankmonitor.payments.common.Money;
import hu.bankmonitor.payments.common.ProblemType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.util.List;

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
 * <p>The path is written out again in the tests that call it, rather than read from the
 * constant below, on {@code AccountController}'s reasoning: a renamed path is a breaking
 * change for the generated frontend types, and a test reading the same constant as the
 * mapping would follow the rename silently instead of failing on it.
 */
@RestController
@RequestMapping(TransferController.PATH)
class TransferController {

	/**
	 * Shared by the mapping above and the {@code Location} header below, which is the one
	 * place two literals in this file would have to be renamed together.
	 *
	 * <p>Package-private because it has to be: a class-level annotation cannot read a
	 * {@code private} member of its own class, qualified or not, so {@code @RequestMapping}
	 * above fixes the floor. The tests in this package can therefore see it, and the rule in
	 * this class's Javadoc that they write the path out instead is a convention rather than
	 * something the compiler enforces.
	 */
	static final String PATH = "/api/transfers";

	private static final String SELF_TRANSFER_DETAIL =
			"A Transfer must move money between two different Accounts.";

	private static final String UNKNOWN_ACCOUNT_DETAIL = "No Account has that identifier.";

	private static final String INSUFFICIENT_FUNDS_DETAIL =
			"The source Account's Available Balance does not cover this Transfer.";

	private static final String CROSS_CURRENCY_DETAIL =
			"This service cannot yet convert between the two Accounts' Currencies.";

	private static final String UNKNOWN_TRANSFER_DETAIL = "No Transfer has that identifier.";

	private final FundsReservation reservation;

	private final TransferLookup transfers;

	private final Clock clock;

	TransferController(FundsReservation reservation, TransferLookup transfers, Clock clock) {
		this.reservation = reservation;
		this.transfers = transfers;
		this.clock = clock;
	}

	/**
	 * Every Transfer in every status, newest first, or the one status {@code status} names.
	 *
	 * <p><b>Every status, which is design decision 19's reading of the requirement rather
	 * than a liberty taken with it.</b> The task asks for executed transfers, written against
	 * a synchronous model where a Transfer is either done or never existed. Under the
	 * asynchronous lifecycle of ADR-0001 a {@code PENDING} Transfer appearing nowhere would
	 * make the only list screen actively misleading: an operator would believe their Transfer
	 * had vanished. The filter is what narrows it to settled Transfers for a caller who wants
	 * that.
	 *
	 * <p>A bare array rather than an object wrapping one, and a status typed as the enum
	 * rather than a string, both on {@code AccountController#listAccounts}'s reasoning: an
	 * envelope earns its place when there is something to put beside the items, and design
	 * decision 31 declines pagination. A status this domain has no name for is refused by the
	 * conversion, and {@code ProblemDocumentAdvice} reports it against {@code status} in the
	 * shape a rejected body field gets.
	 */
	@GetMapping
	List<TransferResponse> listTransfers(@RequestParam(required = false) @Nullable TransferStatus status) {
		return transfers.list(status).stream().map(TransferResponse::of).toList();
	}

	/**
	 * One Transfer, at the URL the {@code 201} above points at. This is the resource that lets
	 * a pending Transfer's state live in the URL rather than in the tab that submitted it, so
	 * a refresh shows where the Transfer has got to instead of losing it.
	 *
	 * <p>The same {@link TransferResponse} the listing sends, member for member. Ticket 22
	 * adds the Check Ledger to this response and to this one only — what a Transfer is waiting
	 * on is what a single Transfer is fetched to find out, and it is not a column.
	 */
	@GetMapping("/{id}")
	TransferResponse fetchTransfer(@PathVariable long id) {
		return TransferResponse.of(transfers.byId(id));
	}

	/**
	 * Answers {@code 201} with the newly {@code PENDING} Transfer, its funds already
	 * reserved on the source Account and no money moved.
	 *
	 * <p>The {@code Location} header points at {@link #fetchTransfer}, which is the resource
	 * ticket 15 built and ticket 14 declined to address before it existed — a header pointing
	 * at a {@code 404} breaks the client that trusts it. It is a relative reference, which RFC
	 * 9110 permits and which is the honest answer here: an absolute URL would have to be built
	 * from the request's own {@code Host}, and this application sits behind a dev-server proxy
	 * on one origin and would sit behind an ingress on another.
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
	ResponseEntity<TransferResponse> requestTransfer(
			@RequestHeader(name = "X-Idempotency-Key", required = false) @NotNull
			@UUID(version = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
					letterCase = UUID.LetterCase.INSENSITIVE, allowNil = false) String idempotencyKey,
			@Valid @RequestBody CreateTransferRequest request) {
		TransferResponse requested =
				TransferResponse.of(reservation.reserve(request.reservationAt(clock.instant())));
		return ResponseEntity.created(URI.create(PATH + "/" + requested.id())).body(requested);
	}

	/**
	 * Each of the four ways a requested Transfer is refused is {@code 422} rather than
	 * {@code 400}. Each is decided against Accounts the request could not see, so the request
	 * was well-formed and this API understood it — which is exactly the distinction the two
	 * statuses draw. A {@code 404} for an unknown Account was rejected for a sharper reason:
	 * this API answers {@code 404} when the <em>path</em> names nothing, which is what
	 * {@link #handleUnknownTransfer} is and what {@code /api/transfers} never is.
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
	 * The one refusal here that is a {@code 404}, and it is the same {@code 404} ticket 05
	 * settled rather than a second meaning for the status: <em>the path names nothing</em>. The
	 * identifier <em>is</em> the path, so an identifier no Transfer has addresses no resource.
	 *
	 * <p>That is what the {@code 422} above it buys. Answering an unknown Account inside a
	 * payload with {@code 404} too would have given one status two meanings, and a client could
	 * not have told "that URL does not exist" from "account 900 does not exist" without parsing
	 * the URN it would then have needed anyway (design decision 14).
	 */
	@ExceptionHandler
	ProblemDetail handleUnknownTransfer(UnknownTransferException refusal) {
		ProblemDetail problem =
				problemOf(HttpStatus.NOT_FOUND, ProblemType.NOT_FOUND, UNKNOWN_TRANSFER_DETAIL);
		problem.setProperty("transferId", refusal.getTransferId());
		return problem;
	}

	/** The status the four refusals above share, which is the only thing they share. */
	private static ProblemDetail refusalOf(ProblemType type, String detail) {
		return problemOf(HttpStatus.UNPROCESSABLE_ENTITY, type, detail);
	}

	/**
	 * The parts every problem document here shares. {@code instance} is left unset on purpose:
	 * the message converter fills it in from the request URI for any {@code ProblemDetail} that
	 * does not name one, so setting it here would be a second copy of the request path to keep
	 * right.
	 *
	 * <p>Every {@code detail} is written in this class rather than taken from the exception, on
	 * {@code ProblemDocumentAdvice}'s reasoning: an exception message is written for a log, and
	 * two of these carry {@code Money}, whose {@code toString} is a record's.
	 */
	private static ProblemDetail problemOf(HttpStatus status, ProblemType type, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(type.uri());
		return problem;
	}
}
