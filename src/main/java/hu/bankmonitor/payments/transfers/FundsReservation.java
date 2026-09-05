package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.accounts.AccountLocking;
import hu.bankmonitor.payments.accounts.LockedAccounts;
import hu.bankmonitor.payments.common.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requests a Transfer: funds reserved on the source Account, nothing moved, and a {@code
 * PENDING} Transfer to hold the request while its Checks are outstanding.
 *
 * <p><b>The order inside {@link #reserve} is the correctness argument</b>, and it is the
 * reason the three statements are not interchangeable:
 *
 * <ol>
 * <li>lock both Accounts, ascending by ID, which {@link AccountLocking} owns;
 * <li><em>then</em> read the source Account's Available Balance and test the amount against
 * it;
 * <li>write the reservation and the Transfer.
 * </ol>
 *
 * <p>Because the check follows the lock, the figure it tests against cannot be invalidated
 * between reading it and acting on it: any other transaction that would spend the same
 * Available Balance is waiting on the row until this one commits, and then reads what this
 * one wrote. Checking first and locking second would compile, pass every single-threaded
 * test, and overdraw under load.
 *
 * <p>Nothing here saves the source Account, and that is not an omission. The Accounts come
 * back from {@link AccountLocking} attached to this transaction's persistence context, so
 * raising the Reserved Amount <em>is</em> the write, flushed on commit alongside the
 * Transfer. There is no other way for this slice to do it either: design decision 30 keeps
 * {@code AccountRepository} package-private, and locking is the whole of what {@code
 * accounts} exports.
 *
 * <p>The Exchange Rate is deliberately not fetched here. Design decision 4 puts it in a
 * phase of its own with no transaction open and no locks held, which is what makes
 * pessimistic locking affordable at all; {@code LockedPathTouchesOnlyTheDatabaseTest} names
 * this class and fails if anything it can reach would wait on a provider.
 *
 * <p><b>Same-Currency Transfers only, and not by a check.</b> Both amounts on the Transfer
 * are denominated by the source Account, so a Transfer between two Currencies is reserved
 * and written with the wrong Currency in the credited column rather than refused. That hole
 * is deliberate and named in {@code docs/deferred.md}: ticket 26 is where an Exchange Rate
 * gives the credited side a figure of its own, and a refusal added here now is a rule ticket
 * 26 would have to reinterpret rather than delete.
 */
@Service
class FundsReservation {

	private final AccountLocking accounts;

	private final TransferRepository transfers;

	FundsReservation(AccountLocking accounts, TransferRepository transfers) {
		this.accounts = accounts;
		this.transfers = transfers;
	}

	/**
	 * Reserves the amount on the source Account and records the {@code PENDING} Transfer it
	 * is held for, in one transaction that writes both or neither.
	 *
	 * <p>The transaction is opened here rather than inherited, which is what {@link
	 * AccountLocking}'s mandatory propagation is checking for. Ticket 16 will widen it to
	 * carry the idempotency record's flip to {@code SUCCEEDED} in the same commit, per
	 * design decision 4.
	 *
	 * @throws hu.bankmonitor.payments.accounts.UnknownAccountException  if either Account ID
	 *                                                                  has no row
	 * @throws hu.bankmonitor.payments.accounts.InsufficientFundsException if the source
	 *                                                                  Account's Available
	 *                                                                  Balance does not
	 *                                                                  cover the amount
	 * @throws IllegalArgumentException if both IDs name the same Account
	 */
	@Transactional
	public Transfer reserve(ReservationRequest request) {
		LockedAccounts locked =
				accounts.lockForTransfer(request.sourceAccountId(), request.destinationAccountId());
		Money amount = new Money(request.amountMinorUnits(), locked.source().getCurrency());

		locked.source().reserve(amount);

		return transfers.save(new Transfer(request.sourceAccountId(), request.destinationAccountId(),
				amount, amount, request.requestedAt()));
	}
}
