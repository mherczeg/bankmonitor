package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.accounts.Account;
import hu.bankmonitor.payments.accounts.AccountLocking;
import hu.bankmonitor.payments.accounts.LockedAccounts;
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
 * <li>check that the amounts phase two resolved are denominated by the Accounts now locked;
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
 * <p><b>The Exchange Rate is deliberately not fetched here, and this class cannot reach the
 * code that would.</b> Design decision 4 puts the fetch in a phase of its own with no
 * transaction open and no locks held, which is what makes pessimistic locking affordable at
 * all. {@link TransferQuotes} is that phase, and it hands the result down as {@link
 * ConvertedAmounts} — a type that names nothing from {@code fx}, so that
 * {@code LockedPathTouchesOnlyTheDatabaseTest}, which walks everything this class can reach
 * and fails on a dependency into that package, keeps holding the line rather than having to
 * make an exception for the ticket that gave this method a rate.
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
	 * <p>The transaction is joined from {@code IdempotentExecution}, which opens it around
	 * this call so that the idempotency record's flip to {@code SUCCEEDED} commits with the
	 * reservation it reports (design decision 4). {@code @Transactional} stays because the
	 * mandatory propagation of {@link AccountLocking} is checking that <em>some</em>
	 * transaction is open, and because a caller reaching this method any other way must
	 * still get one.
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
	 * @param amounts what {@link TransferQuotes} resolved for this request, outside this
	 *                transaction and before any lock was taken
	 * @throws SelfTransferNotAllowedException if both IDs name the same Account
	 * @throws IllegalStateException if the Accounts are not denominated as the amounts say
	 */
	@Transactional
	public Transfer reserve(ReservationRequest request, ConvertedAmounts amounts) {
		if (request.sourceAccountId() == request.destinationAccountId()) {
			throw new SelfTransferNotAllowedException(request.sourceAccountId());
		}

		LockedAccounts locked =
				accounts.lockForTransfer(request.sourceAccountId(), request.destinationAccountId());
		requireTheAmountsMatchTheLockedAccounts(locked, amounts);

		locked.source().reserve(amounts.debitedAmount());

		Transfer requested = transfers.save(new Transfer(request.sourceAccountId(),
				request.destinationAccountId(), amounts, request.requestedAt()));
		ledger.openFor(requested);
		return requested;
	}

	/**
	 * The seam where phase two's unlocked read meets the locked one, checked rather than
	 * assumed.
	 *
	 * <p>It cannot fire today: an Account's Currency is fixed when it is opened and nothing
	 * changes it, which is the entire argument for reading it without a lock in the first
	 * place. What it holds is the day that stops being true. A redenomination — or an
	 * Account deleted and its ID reused — would otherwise land here as two amounts quietly
	 * denominated in a Currency neither Account holds, and {@link Account#reserve} would
	 * refuse the debited side while nothing at all questioned the credited one.
	 *
	 * <p>Unchecked and uncaught, because a caller cannot act on it and an operator did
	 * nothing wrong.
	 */
	private static void requireTheAmountsMatchTheLockedAccounts(
			LockedAccounts locked, ConvertedAmounts amounts) {
		if (locked.source().getCurrency() != amounts.debitedAmount().currency()
				|| locked.destination().getCurrency() != amounts.creditedAmount().currency()) {
			throw new IllegalStateException(
					"a %s to %s transfer was quoted, and the accounts are denominated in %s and %s"
							.formatted(amounts.debitedAmount().currency(), amounts.creditedAmount().currency(),
									locked.source().getCurrency(), locked.destination().getCurrency()));
		}
	}
}
