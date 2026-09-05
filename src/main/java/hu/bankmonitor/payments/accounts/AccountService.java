package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * What the accounts slice can be asked to do, and where the transaction around it starts.
 *
 * <p>Design decision 30 forbids a controller holding a repository — asserted by
 * {@code ModuleBoundariesHoldTest} — because a controller that reaches persistence
 * directly has skipped the layer that owns the transaction. Today the work either side of
 * that boundary is one call each way; the moment ticket 13 reserves against an Account
 * there will be more.
 *
 * <p>The class is package-private and the methods are public, which looks backwards and is
 * not: proxy-based AOP silently ignores {@code @Transactional} on a non-public method, so a
 * demotion here would leave a method that reads as transactional and is not, with no error
 * to read (design decision 30).
 */
@Service
class AccountService {

	private final AccountRepository accounts;

	AccountService(AccountRepository accounts) {
		this.accounts = accounts;
	}

	/** Every Account there is. Design decision 31 declines pagination; this is all of them. */
	@Transactional(readOnly = true)
	public List<Account> listAll() {
		return accounts.findAllByOrderByIdAsc();
	}

	/**
	 * Opens an Account holding the given amount, in the currency that amount is
	 * denominated in, and returns it with the identifier the database gave it.
	 */
	@Transactional
	public Account open(Money openingBalance) {
		return accounts.save(new Account(openingBalance));
	}
}
