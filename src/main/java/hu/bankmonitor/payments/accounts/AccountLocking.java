package hu.bankmonitor.payments.accounts;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

/**
 * Takes the row locks a Transfer needs on the two Accounts it moves money between.
 *
 * <p>The one operation the {@code accounts} slice exports for {@code transfers}, and what
 * {@link AccountRepository} staying package-private buys: the repository's own read and
 * write methods belong to this package's endpoints, so from outside it there is no way to
 * reach a balance except through this class, and no way to take the locks in an order
 * other than the one {@link #inLockOrder} imposes.
 *
 * <p>Nothing here reaches past the database. That is a requirement rather than an
 * observation — a transaction holding these locks must never wait on a slow provider —
 * and it is why design decision 4 fetches the Exchange Rate in a phase of its own, before
 * any of this runs. {@code LockedPathTouchesOnlyTheDatabaseTest} holds the line.
 */
@Component
public class AccountLocking {

	private final AccountRepository accounts;

	AccountLocking(AccountRepository accounts) {
		this.accounts = accounts;
	}

	/**
	 * Locks both Accounts for the rest of the caller's transaction and hands them back by
	 * role. The caller is expected to read the source's Available Balance and write both
	 * rows afterwards, all of it inside that same transaction.
	 *
	 * <p>Mandatory propagation rather than an assumption: a pessimistic lock outside a
	 * transaction would be released before the balance it guards had been checked, and a
	 * missing {@code @Transactional} on the caller is the kind of mistake that produces a
	 * suite that passes and a race in production.
	 *
	 * @throws UnknownAccountException  if either ID has no row
	 * @throws IllegalArgumentException if both IDs are the same Account
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public LockedAccounts lockForTransfer(long sourceAccountId, long destinationAccountId) {
		if (sourceAccountId == destinationAccountId) {
			throw new IllegalArgumentException(
					"account " + sourceAccountId + " cannot be both sides of a transfer");
		}

		Map<Long, Account> locked = new HashMap<>();
		for (long accountId : inLockOrder(sourceAccountId, destinationAccountId)) {
			locked.put(accountId, lockForUpdate(accountId));
		}
		return new LockedAccounts(locked.get(sourceAccountId), locked.get(destinationAccountId));
	}

	/**
	 * Ascending Account ID, never the roles the Transfer gives the two.
	 *
	 * <p>This is the deadlock the ordering exists to prevent: were locks taken by role,
	 * a Transfer 5 → 9 would hold 5 and want 9 while a simultaneous 9 → 5 held 9 and
	 * wanted 5, and neither could give way. Ascending order makes both contend for 5
	 * first, so the loser waits holding nothing. There is no cycle to form, which makes
	 * deadlock structurally impossible rather than merely unlikely — design decision 6.
	 *
	 * <p>Any total order over the IDs would do; ascending is the one that reads in a log.
	 */
	private static List<Long> inLockOrder(long sourceAccountId, long destinationAccountId) {
		return LongStream.of(sourceAccountId, destinationAccountId).sorted().boxed().toList();
	}

	private Account lockForUpdate(long accountId) {
		return accounts.findAndLockById(accountId)
				.orElseThrow(() -> new UnknownAccountException(accountId));
	}
}
