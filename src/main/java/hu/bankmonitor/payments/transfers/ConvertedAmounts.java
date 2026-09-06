package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.common.Money;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * What a Transfer's two sides come to, and the Exchange Rate that relates them: everything
 * design decision 4's phase two resolves, in the shape phase three writes.
 *
 * <p><b>Nothing here comes from the {@code fx} package, and that is structural rather than
 * incidental.</b> This record is a parameter of {@link FundsReservation}, which holds the
 * Account row locks, and {@code LockedPathTouchesOnlyTheDatabaseTest} walks everything that
 * class can reach. Carrying an {@code ExchangeRate} here would put the Exchange Rate port's
 * package inside the locked path's reach, and the rule that keeps a provider call out of a
 * transaction would have to be weakened to allow it. {@link TransferQuotes} does the
 * unpacking, above the lock, where a call to a third party belongs.
 *
 * <p>The rate and its timestamp are {@code null} exactly when the two Accounts share a
 * Currency. A rate of one would read as a quote and no quote was fetched — the provider was
 * never called, which is the point — and design decision 25 is emphatic that this
 * application does not invent facts about somebody else's data. The invariant is enforced
 * here, and again as a {@code check} constraint in {@code V6__transfer_exchange_rate.sql},
 * because this record is not the only way a row can be written.
 *
 * <p><b>Both places state the same three things</b>, down to the rate being positive.
 * {@code ExchangeRate} already refuses a non-positive quote a layer up, so nothing reaches
 * here with one; the check is what stops the constraint and this constructor drifting into
 * two different invariants, which is the failure mode of enforcing one rule twice.
 *
 * @param debitedAmount         what leaves the source Account, in the source Account's
 *                              Currency
 * @param creditedAmount        what arrives at the destination Account, in its own
 * @param exchangeRate          destination Currency units per one of the source's, as the
 *                              provider quoted it, or {@code null} for a Transfer that
 *                              needed none
 * @param exchangeRateFetchedAt when this application received that quote, or {@code null}
 *                              with it
 */
record ConvertedAmounts(
		Money debitedAmount,
		Money creditedAmount,
		@Nullable BigDecimal exchangeRate,
		@Nullable Instant exchangeRateFetchedAt) {

	ConvertedAmounts {
		Objects.requireNonNull(debitedAmount, "a transfer debits an amount");
		Objects.requireNonNull(creditedAmount, "a transfer credits an amount");
		if (needsARate(debitedAmount, creditedAmount) != (exchangeRate != null)) {
			throw new IllegalArgumentException(
					"a %s to %s transfer %s an Exchange Rate".formatted(debitedAmount.currency(),
							creditedAmount.currency(), exchangeRate == null ? "needs" : "needs no"));
		}
		if ((exchangeRate == null) != (exchangeRateFetchedAt == null)) {
			throw new IllegalArgumentException("an Exchange Rate is stored with the moment it was fetched");
		}
		if (exchangeRate != null && exchangeRate.signum() <= 0) {
			throw new IllegalArgumentException("an Exchange Rate is a positive number, not " + exchangeRate);
		}
	}

	/**
	 * A Transfer between two Accounts in one Currency, which is the same figure on both
	 * sides and no provider call — the conversion happened, at a rate of one, and nobody was
	 * asked what that rate was.
	 */
	static ConvertedAmounts unconverted(Money amount) {
		return new ConvertedAmounts(amount, amount, null, null);
	}

	private static boolean needsARate(Money debitedAmount, Money creditedAmount) {
		return debitedAmount.currency() != creditedAmount.currency();
	}
}
