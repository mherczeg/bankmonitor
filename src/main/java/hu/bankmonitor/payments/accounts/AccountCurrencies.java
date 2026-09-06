package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Currency;
import org.springframework.stereotype.Component;

/**
 * What Currency each of two Accounts is denominated in, read without taking a lock.
 *
 * <p>The second thing this slice exports, and the first that hands out an Account's data
 * outside {@link AccountLocking}'s lock. <b>That is safe for this one field and for nothing
 * else on the row.</b> A Currency is fixed when the Account is opened and there is no
 * operation anywhere that changes it, so a read of it cannot go stale between being taken
 * and being acted on — where a balance read the same way is exactly the race design
 * decision 6 exists to prevent.
 *
 * <p>It exists because of the ordering design decision 4 imposes: the Exchange Rate is
 * fetched with no transaction open and no lock held, and the pair to quote is a fact about
 * two Accounts. Something has to read those two Currencies before any lock is taken, or the
 * provider call ends up inside the transaction that the whole locking design depends on
 * keeping short.
 *
 * <p>The figure that is <em>not</em> safe to read here — the Available Balance — is why this
 * returns Currencies rather than Accounts. A caller holding two unlocked {@link Account}s
 * could read a balance off one and believe it.
 */
@Component
public class AccountCurrencies {

	private final AccountRepository accounts;

	AccountCurrencies(AccountRepository accounts) {
		this.accounts = accounts;
	}

	/**
	 * Both Currencies, in one call, because a caller needing one of them needs the other to
	 * do anything with it.
	 *
	 * <p>An Account may be gone by the time the lock is taken — nothing deletes one today,
	 * and this is not what stands between a Transfer and a missing Account. The refusal that
	 * counts is {@link AccountLocking#lockForTransfer}'s, under the lock. This one is here
	 * because there is no Currency to answer with, and it blames the source before the
	 * destination where the locked refusal blames the lower ID first: with two unknown
	 * Accounts the two name different ones, and both are true.
	 *
	 * @throws UnknownAccountException if either ID has no row
	 */
	public TransferCurrencies forTransfer(long sourceAccountId, long destinationAccountId) {
		return new TransferCurrencies(currencyOf(sourceAccountId), currencyOf(destinationAccountId));
	}

	private Currency currencyOf(long accountId) {
		return accounts.findCurrencyById(accountId)
				.orElseThrow(() -> new UnknownAccountException(accountId));
	}
}
