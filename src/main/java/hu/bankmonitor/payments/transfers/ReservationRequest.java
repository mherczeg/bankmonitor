package hu.bankmonitor.payments.transfers;

import java.time.Instant;
import java.util.Objects;

/**
 * What a caller has to say to request a Transfer: the two Accounts, how much to move, and
 * when it was asked for.
 *
 * <p><b>The amount is a bare count of Minor Units and not {@link
 * hu.bankmonitor.payments.common.Money}</b>, which is the one place in this context where a
 * number without a Currency is the honest shape. The Currency of a Transfer is the source
 * Account's, and the source Account is only read under the row lock design decision 6
 * requires — so a caller could only name a Currency here by reading the Account first,
 * outside the lock, which is the read that design decision exists to forbid. The count
 * becomes Money inside {@link FundsReservation}, at the first moment there is an Account to
 * denominate it.
 *
 * <p>Design decision 15 locks the Exchange Rate at request time, so ticket 26 will bring a
 * second amount for the credited side. Until then both sides of a Transfer are the same
 * figure and this carries it once.
 */
public record ReservationRequest(
		long sourceAccountId,
		long destinationAccountId,
		long amountMinorUnits,
		Instant requestedAt) {

	public ReservationRequest {
		Objects.requireNonNull(requestedAt, "a transfer is requested at a time");
	}
}
