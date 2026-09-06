package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.common.Currency;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One Transfer as the API reports it: where it is going, how much on each side, and where
 * it has got to.
 *
 * <p><b>One representation for every endpoint that answers with a Transfer</b>, on
 * {@code AccountResponse}'s precedent. A leaner shape for the Transfer just requested would
 * hand the frontend two Transfer types generated from one document, and the screen a client
 * lands on after submitting is the same screen it refreshes later.
 *
 * <p><b>Two amounts, each with its own Currency</b>, because the two Accounts may be
 * denominated differently: the debited amount is what leaves the source Account, the
 * credited amount is what arrives at the destination. Today they agree — a same-Currency
 * Transfer carries the same figure twice, which is the honest reading of a conversion at a
 * rate of one — and ticket 26 is where they stop agreeing. A shape carrying one Currency
 * would have to change then, and it is the client that would break.
 *
 * <p>Each amount is a whole count of Minor Units and its name says so; the per-Currency
 * decimal places live at the edges that parse and display an amount, never in transit.
 *
 * <p>The two Accounts are named {@code from} and {@code to} rather than by their roles,
 * matching {@link CreateTransferRequest}: a client reads the Transfer it just posted back
 * out of this shape, and one wire vocabulary is what makes that a recognisable round trip.
 *
 * <p><b>Every member is listed as required, because springdoc cannot work that out</b>, on
 * {@code AccountResponse}'s precedent: it derives {@code required} from constraint
 * annotations and a response is never validated, so an unannotated response record publishes
 * a schema on which every member is optional — and ticket 32's generated types would then
 * accept a mock that simply omits the status. {@code TransferListingTest} holds the list to
 * this record's components, so adding a member and forgetting the list fails rather than
 * quietly narrowing the contract.
 */
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
		Instant createdAt) {

	static TransferResponse of(Transfer transfer) {
		return new TransferResponse(
				transfer.getId(),
				transfer.getSourceAccountId(),
				transfer.getDestinationAccountId(),
				transfer.getStatus(),
				transfer.getDebitedAmount().minorUnits(),
				transfer.getDebitedAmount().currency(),
				transfer.getCreditedAmount().minorUnits(),
				transfer.getCreditedAmount().currency(),
				transfer.getCreatedAt());
	}
}
