package hu.bankmonitor.payments.accounts;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * What the accounts slice can be asked to do, and where the transaction around it starts.
 *
 * <p>Today it only forwards a query, and that is still where it belongs. Design decision
 * 30 forbids a controller holding a repository — asserted by
 * {@code ModuleBoundariesHoldTest} — because a controller that reaches persistence
 * directly has skipped the layer that owns the transaction. There is nothing here yet for
 * that layer to do beyond declaring it, and the moment ticket 09 creates an Account or
 * ticket 13 reserves against one there will be.
 *
 * <p>The class is package-private and {@link #listAll()} is public, which looks backwards
 * and is not: proxy-based AOP silently ignores {@code @Transactional} on a non-public
 * method, so a demotion here would leave a method that reads as transactional and is not
 * (design decision 30).
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
}
