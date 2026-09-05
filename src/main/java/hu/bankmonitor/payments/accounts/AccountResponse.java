package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Currency;

/**
 * One Account as the API reports it: what it holds, what is spoken for, and what is left
 * to spend.
 *
 * <p><b>One currency for all three figures</b>, rather than a currency beside each. An
 * Account is denominated once and the {@code accounts_one_currency} constraint holds the
 * table to it, so a shape carrying three currencies could express a state the database
 * refuses.
 *
 * <p><b>Every amount is a whole count of Minor Units, and its name says so.</b> Read as a
 * decimal, {@code 250000} is off by a factor of a hundred in EUR and correct in HUF, and
 * nothing about the number itself tells the two apart. The per-currency decimal places
 * live at the edges that parse and display an amount — the frontend's formatting module of
 * ticket 33 is the one on this side of the wire — and never in transit.
 *
 * <p><b>The Available Balance is reported, not stored.</b> Design decision 13 keeps two
 * figures on the Account and derives the third, so it is computed here on the way out
 * rather than persisted where a write could leave it disagreeing with the two it comes
 * from. It is sent rather than left for the client to subtract because it is the figure an
 * overdraft check tests against, and a client that computed it would be a second
 * implementation of the rule.
 */
record AccountResponse(
		long id,
		Currency currency,
		long balanceMinorUnits,
		long reservedAmountMinorUnits,
		long availableBalanceMinorUnits) {

	static AccountResponse of(Account account) {
		return new AccountResponse(
				account.getId(),
				account.getCurrency(),
				account.getBalance().minorUnits(),
				account.getReservedAmount().minorUnits(),
				account.getAvailableBalance().minorUnits());
	}
}
