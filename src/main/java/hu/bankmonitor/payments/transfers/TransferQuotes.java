package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.accounts.AccountCurrencies;
import hu.bankmonitor.payments.accounts.TransferCurrencies;
import hu.bankmonitor.payments.common.Money;
import hu.bankmonitor.payments.fx.ConversionResult;
import hu.bankmonitor.payments.fx.CurrencyConversion;
import hu.bankmonitor.payments.fx.ExchangeRate;
import hu.bankmonitor.payments.fx.ExchangeRateProvider;
import org.springframework.stereotype.Service;

/**
 * Design decision 4's phase two: what a requested Transfer comes to on both sides, worked
 * out after the Idempotency Key is claimed and before any lock is taken.
 *
 * <p><b>Nothing here opens a transaction, and that is the whole reason this class is
 * separate from {@link FundsReservation}.</b> The Exchange Rate provider is somebody else's
 * server and it is unreliable by design; a call to it inside the transaction that holds two
 * Account row locks would make every other Transfer touching either Account wait on a third
 * party. Design decision 6 calls that placement the thing that makes pessimistic locking
 * affordable at all. {@code TheRateIsFetchedWithNoTransactionOpenTest} asserts it against
 * the running application rather than leaving it to this paragraph.
 *
 * <p>The two Currencies are read unlocked, which {@link AccountCurrencies} explains is safe
 * for that field and nothing else on the row. It is what lets the question <em>does this
 * Transfer need a rate at all</em> be asked before the provider is: a same-Currency Transfer
 * makes no call, so an outage at the provider cannot stop one.
 *
 * <p>The conversion is here rather than under the lock for the same reason the fetch is —
 * {@link CurrencyConversion} lives in {@code fx}, and the locked path is walked for
 * dependencies on that package precisely so a provider call cannot hide one hop down. What
 * phase three receives is {@link ConvertedAmounts}, which names no type from {@code fx} at
 * all.
 */
@Service
class TransferQuotes {

	private final AccountCurrencies currencies;

	private final ExchangeRateProvider rates;

	TransferQuotes(AccountCurrencies currencies, ExchangeRateProvider rates) {
		this.currencies = currencies;
		this.rates = rates;
	}

	/**
	 * Both sides of the requested Transfer, converted at a rate fetched for this request.
	 *
	 * <p>The Currencies come from the Accounts rather than from the request, which carries
	 * none: a Transfer is denominated by its source Account, and
	 * {@link CreateTransferRequest} has why letting a caller say otherwise would create a
	 * claim the Accounts can contradict.
	 *
	 * @throws hu.bankmonitor.payments.accounts.UnknownAccountException if either Account ID
	 *                                                                 has no row
	 * @throws ConversionRoundsToZeroException if the amount is worth less than half a Minor
	 *                                         Unit of the destination Currency
	 * @throws hu.bankmonitor.payments.fx.ExchangeRateUnavailableException if the provider did
	 *                                                                    not answer within
	 *                                                                    its retry budget
	 */
	ConvertedAmounts convert(ReservationRequest request) {
		TransferCurrencies pair =
				currencies.forTransfer(request.sourceAccountId(), request.destinationAccountId());
		Money debitedAmount = new Money(request.amountMinorUnits(), pair.source());
		if (pair.areTheSame()) {
			return ConvertedAmounts.unconverted(debitedAmount);
		}

		ExchangeRate quote = rates.exchangeRateFor(pair.source(), pair.destination());
		return switch (CurrencyConversion.convert(debitedAmount, pair.destination(), quote.rate())) {
			case ConversionResult.Converted(Money creditedAmount) -> new ConvertedAmounts(
					debitedAmount, creditedAmount, quote.rate(), quote.fetchedAt());
			case ConversionResult.RoundsToZero() -> throw new ConversionRoundsToZeroException(
					debitedAmount, pair.destination(), quote.rate());
		};
	}
}
