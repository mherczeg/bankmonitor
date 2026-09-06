package hu.bankmonitor.payments.transfers;

import com.fasterxml.jackson.annotation.JsonInclude;
import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.transfers.checks.CheckState;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One Transfer as the API reports it: where it is going, how much on each side, and where
 * it has got to.
 *
 * <p><b>One representation for every endpoint that answers with a Transfer</b>, on
 * {@code AccountResponse}'s precedent. A leaner shape for the Transfer just requested would
 * hand the frontend two Transfer types generated from one document, and the screen a client
 * lands on after submitting is the same screen it refreshes later.
 *
 * <p><b>{@code checks} is the one member only the single-Transfer response sends</b>, and it
 * is what keeps that claim true through ticket 22. A Transfer's Check Ledger is what a
 * Transfer is fetched to find out — the answer to "why is this stuck" — and it is not a
 * column: a listing carrying every Transfer's ledger would read a second table per row to
 * fill a screen that renders none of it. A record of its own for the detail endpoint was the
 * alternative, and it would have published a second Transfer-shaped schema whose nine other
 * members are this one's, kept in step by hand.
 *
 * <p>The cost is paid in the document and is worth naming: {@code checks} is left out of
 * {@code requiredProperties}, so ticket 32's generated type says it may be missing — on ticket
 * 41's screen, which exists to render it. That is the trade this shape makes, and it is the
 * exact case design decision 32 had in mind when it rejected a customizer marking every
 * property required: a rule that would lie the first time a response had a genuinely optional
 * member. This is one of them.
 *
 * <p><b>Two amounts, each with its own Currency</b>, because the two Accounts may be
 * denominated differently: the debited amount is what leaves the source Account, the
 * credited amount is what arrives at the destination. A same-Currency Transfer carries the
 * same figure twice, which is the honest reading of a conversion at a rate of one; a
 * cross-Currency one carries two, and the rate between them.
 *
 * <p><b>The Exchange Rate and the moment it was fetched are reported, not just used.</b>
 * Design decision 15 locks the rate onto the Transfer for its life precisely so that the
 * figure an operator was shown is the figure that settles, and a conversion nobody can read
 * back is one nobody can audit. The timestamp travels with it because a rate without one
 * cannot be checked against anything.
 *
 * <p>Each amount is a whole count of Minor Units and its name says so; the per-Currency
 * decimal places live at the edges that parse and display an amount, never in transit.
 *
 * <p>The two Accounts are named {@code from} and {@code to} rather than by their roles,
 * matching {@link CreateTransferRequest}: a client reads the Transfer it just posted back
 * out of this shape, and one wire vocabulary is what makes that a recognisable round trip.
 *
 * <p><b>Every member that is always present is listed as required, because springdoc cannot
 * work that out</b>, on {@code AccountResponse}'s precedent: it derives {@code required} from
 * constraint annotations and a response is never validated, so an unannotated response record
 * publishes a schema on which every member is optional — and ticket 32's generated types
 * would then accept a mock that simply omits the status.
 *
 * <p>Three members are left out, each for a reason stated where it is declared: {@code checks},
 * which only the single-Transfer endpoint sends, and the two rate members, which are absent for
 * a same-Currency Transfer. A schema calling any of them required would generate a client type
 * promising something this API does not always send. {@code TransferListingTest} holds the list
 * to this record's components minus those three, so adding a member and forgetting the list
 * fails rather than quietly narrowing the contract, and a fourth optional member cannot be
 * added without meeting this decision.
 *
 * <p><b>{@code NON_NULL} is what makes "absent" true on the wire.</b> Jackson's default is to
 * write a null member out as {@code "exchangeRate":null}, which is a different contract from
 * the one the schema above publishes and from the {@code exchangeRate?: number} the generated
 * client gets: an optional member that is missing, not one that is present and null. It is set
 * on this record rather than globally because the problem document's {@code ValidationError}
 * reports a class-level violation as a null {@code field}, and that is a member whose null is
 * worth sending.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(requiredProperties = {
		"id", "fromAccountId", "toAccountId", "status",
		"debitedAmountMinorUnits", "debitedAmountCurrency",
		"creditedAmountMinorUnits", "creditedAmountCurrency", "createdAt"})
record TransferResponse(
		long id,
		long fromAccountId,
		long toAccountId,
		TransferStatus status,
		long debitedAmountMinorUnits,
		Currency debitedAmountCurrency,
		long creditedAmountMinorUnits,
		Currency creditedAmountCurrency,

		/**
		 * Destination Currency units per one of the source's, as the provider quoted it when
		 * the Transfer was requested — the figure that relates the two amounts above and the
		 * one it will settle at.
		 *
		 * <p>Null for a Transfer between two Accounts in one Currency: no rate was fetched,
		 * and reporting one would tell a client about a quote that never happened. So the two
		 * rate members are deliberately absent from {@code requiredProperties}, and the
		 * generated types make a client handle it.
		 */
		@Nullable BigDecimal exchangeRate,

		@Nullable Instant exchangeRateFetchedAt,

		Instant createdAt,

		/** The Check Ledger, sent by the single-Transfer endpoint and by no other. */
		@Nullable List<CheckResponse> checks) {

	/** Every endpoint that answers with a Transfer and is not the one addressed by its ID. */
	static TransferResponse of(Transfer transfer) {
		return from(transfer, null);
	}

	/**
	 * The single-Transfer response, which is the one place the Check Ledger goes on the wire.
	 *
	 * @param ledger every Check the Transfer requires, read in the same transaction as the
	 *               Transfer so that the status and the ledger explaining it are one snapshot
	 */
	static TransferResponse withChecks(Transfer transfer, List<CheckState> ledger) {
		return from(transfer, ledger.stream().map(CheckResponse::of).toList());
	}

	private static TransferResponse from(Transfer transfer, @Nullable List<CheckResponse> checks) {
		return new TransferResponse(
				transfer.getId(),
				transfer.getSourceAccountId(),
				transfer.getDestinationAccountId(),
				transfer.getStatus(),
				transfer.getDebitedAmount().minorUnits(),
				transfer.getDebitedAmount().currency(),
				transfer.getCreditedAmount().minorUnits(),
				transfer.getCreditedAmount().currency(),
				transfer.getExchangeRate(),
				transfer.getExchangeRateFetchedAt(),
				transfer.getCreatedAt(),
				checks);
	}
}
