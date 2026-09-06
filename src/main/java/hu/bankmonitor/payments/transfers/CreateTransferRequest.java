package hu.bankmonitor.payments.transfers;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * What requesting a Transfer takes: the Account the money leaves, the Account it arrives
 * at, and how much to move.
 *
 * <p><b>There is no Currency here, and its absence is the point.</b> A Transfer is
 * denominated by its source Account, so a payload that named a Currency would be able to
 * express a claim the Account can contradict — EUR out of a HUF Account — and something
 * would then have to decide which of the two to believe. Leaving it out deletes that class
 * of bug rather than validating it away, and the frontend's form has no Currency input for
 * the same reason.
 *
 * <p>The amount is a whole count of Minor Units and the field name says so, on
 * {@code CreateAccountRequest}'s precedent: {@code 10050} in a field called {@code amount}
 * reads as ten thousand and fifty euros to one caller and as a hundred euros fifty to the
 * next.
 *
 * <p>All three are boxed rather than primitive. A primitive that no JSON member filled
 * would arrive as a perfectly valid zero, so an absent field would be a Transfer of nothing
 * out of Account {@code 0} instead of being refused, and {@code @NotNull} on a primitive
 * can never fire.
 */
record CreateTransferRequest(

		@NotNull Long fromAccountId,

		@NotNull Long toAccountId,

		@NotNull @Positive Long amountMinorUnits) {

	/**
	 * The same request in the shape the reservation takes it, stamped with the instant it
	 * was asked for — never called before validation has run, which is what lets it
	 * dereference all three.
	 */
	ReservationRequest reservationAt(Instant requestedAt) {
		return new ReservationRequest(fromAccountId, toAccountId, amountMinorUnits, requestedAt);
	}
}
