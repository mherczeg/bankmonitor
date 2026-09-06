package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.accounts.Account;
import hu.bankmonitor.payments.accounts.AccountLocking;
import hu.bankmonitor.payments.accounts.LockedAccounts;
import hu.bankmonitor.payments.common.Money;
import hu.bankmonitor.payments.transfers.checks.CheckLedger;
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
 * <li>refuse a self-Transfer, which the request decides on its own;
 * <li>lock both Accounts, ascending by ID, which {@link AccountLocking} owns;
 * <li>refuse a cross-Currency Transfer, at the first moment both Currencies are known;
 * <li><em>then</em> read the source Account's Available Balance and test the amount against
 * it;
 * <li>write the reservation, the Transfer, and the Check Ledger the Transfer will be
 * settled or rejected against.
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
 * <p><b>Same-Currency Transfers only, and refused rather than written.</b> Both amounts on
 * the Transfer are denominated by the source Account, so a Transfer between two Currencies
 * has no honest figure for the credited side. Step 3 refuses it; it does not sit later,
 * because {@link Account#reserve} <em>is</em> the write and a refusal after it has already
 * recorded the reservation it meant to prevent. What the refusal names is a capability this
 * service lacks rather than a rule it keeps, so ticket 26 gives the credited side an
 * Exchange Rate of its own and deletes the check along with {@link
 * CrossCurrencyTransferNotSupportedException}.
 */
@Service
class FundsReservation {

	private final AccountLocking accounts;

	private final TransferRepository transfers;

	private final CheckLedger ledger;

	FundsReservation(AccountLocking accounts, TransferRepository transfers, CheckLedger ledger) {
		this.accounts = accounts;
		this.transfers = transfers;
		this.ledger = ledger;
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
	 * <p>The Check Ledger is opened last because its rows point at the Transfer's ID, and
	 * inside the same transaction because a {@code PENDING} Transfer with an empty Check
	 * Ledger is one nothing will ever settle, expire or explain. {@link CheckLedger#openFor}
	 * refuses to run without a transaction, and refuses to open a ledger with no Checks in
	 * it, so the ordering is the only part of that left here.
	 *
	 * @throws hu.bankmonitor.payments.accounts.UnknownAccountException  if either Account ID
	 *                                                                  has no row
	 * @throws hu.bankmonitor.payments.accounts.InsufficientFundsException if the source
	 *                                                                  Account's Available
	 *                                                                  Balance does not
	 *                                                                  cover the amount
	 * @throws SelfTransferNotAllowedException if both IDs name the same Account
	 * @throws CrossCurrencyTransferNotSupportedException if the two Accounts are denominated
	 *                                                   differently
	 */
	@Transactional
	public Transfer reserve(ReservationRequest request) {
		if (request.sourceAccountId() == request.destinationAccountId()) {
			throw new SelfTransferNotAllowedException(request.sourceAccountId());
		}

		LockedAccounts locked =
				accounts.lockForTransfer(request.sourceAccountId(), request.destinationAccountId());
		if (locked.source().getCurrency() != locked.destination().getCurrency()) {
			throw new CrossCurrencyTransferNotSupportedException(locked.source().getCurrency(),
					locked.destination().getCurrency());
		}

		Money amount = new Money(request.amountMinorUnits(), locked.source().getCurrency());

		locked.source().reserve(amount);

		Transfer requested = transfers.save(new Transfer(request.sourceAccountId(),
				request.destinationAccountId(), amount, amount, request.requestedAt()));
		ledger.openFor(requested);
		return requested;
	}
}
