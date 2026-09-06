package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.accounts.AccountLocking;
import hu.bankmonitor.payments.accounts.LockedAccounts;
import hu.bankmonitor.payments.transfers.checks.Check;
import hu.bankmonitor.payments.transfers.checks.CheckLedger;
import hu.bankmonitor.payments.transfers.checks.LedgerDecision;
import hu.bankmonitor.payments.transfers.checks.Verdict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place a Transfer's lifecycle advances: one Check answers, and the Transfer
 * settles, is rejected, or goes on waiting.
 *
 * <p>The seam every adapter sits over. Ticket 21 puts an HTTP callback in front of this and
 * a broker consumer could replace it later without the domain changing, because nothing here
 * knows how the Verdict arrived. Which is also why the refusals below are exceptions rather
 * than status codes — {@link UnknownTransferException} and
 * {@link TransferNotPendingException} are facts about the Transfer, and the mapping from a
 * fact to a response is the adapter's.
 *
 * <p><b>The order inside {@link #recordVerdict} is the correctness argument</b>, exactly as
 * it is in {@link FundsReservation}:
 *
 * <ol>
 * <li>take the row lock on the Transfer, which serialises everything deciding its next
 * status;
 * <li><em>then</em> read the status, and refuse a Transfer that has already finished;
 * <li>answer the Check and ask the ledger what follows;
 * <li>on a decision that ends the Transfer, lock both Accounts, claim the transition, and
 * move the money;
 * <li>announce the status the Transfer reached, which {@link LifecycleHints} defers until
 * this transaction has committed.
 * </ol>
 *
 * <p>Step 1 is what makes concurrent Verdicts safe — not the conditional update in step 4,
 * which looks like it should be enough and is not, because the failure it misses is two
 * Verdicts that both decide to wait and never reach an update at all.
 * {@link TransferTransitions#findAndLockById} is where that argument is written down at
 * length, and it is the method to read before changing this order.
 *
 * <p><b>Lock order is Transfer first, then Accounts ascending</b>, and it has to be the same
 * everywhere or the ordering buys nothing. It is acyclic against the reservation, which takes
 * Account locks only and never waits on a Transfer row that already exists.
 *
 * <p>Rejection locks both Accounts although only the source moves. That is not an oversight:
 * {@link AccountLocking#lockForTransfer} is the whole of what {@code accounts} exports, and
 * design decision 30 keeps its repository package-private precisely so there is no second way
 * to reach a balance. A lock on an Account nothing writes costs a row lock held for the rest
 * of a short transaction, which is cheaper than a second entry point into the slice.
 *
 * <p>Nothing here saves either Account or the Transfer, for {@link FundsReservation}'s
 * reason: the Accounts come back from {@link AccountLocking} attached to this transaction's
 * persistence context, so moving the money <em>is</em> the write, and the status moves by a
 * guarded update rather than by a mutator {@link Transfer} deliberately does not have.
 */
@Service
class VerdictRecording {

	private final AccountLocking accounts;

	private final TransferTransitions transfers;

	private final CheckLedger ledger;

	private final LifecycleHints hints;

	VerdictRecording(AccountLocking accounts, TransferTransitions transfers, CheckLedger ledger,
			LifecycleHints hints) {
		this.accounts = accounts;
		this.transfers = transfers;
		this.ledger = ledger;
		this.hints = hints;
	}

	/**
	 * Records one Check's answer for one Transfer and returns the status the Transfer is in
	 * once it has been acted on, in one transaction that writes the answer and its
	 * consequences together or neither.
	 *
	 * <p>Together is the requirement rather than a convenience. An answer committed apart from
	 * the movement it authorises is either a settled Transfer nobody paid or a payment against
	 * a ledger that does not say why, and {@link CheckLedger#record} states that to the
	 * container by refusing to run without a transaction of the caller's.
	 *
	 * <p><b>A redelivery onto a finished Transfer is refused rather than absorbed.</b> A Check
	 * service delivers at least once, so the Verdict that settled a Transfer will arrive
	 * again; it meets the terminal-status refusal in step 2 and never reaches the ledger. What
	 * makes that safe rather than merely strict is that the money moved once and
	 * {@link TransferNotPendingException} carries the status, so the caller learns that its
	 * report landed and what it did. Absorbing the redelivery instead would mean reading the
	 * ledger before the refusal to find out whether this Check had already given this answer,
	 * which buys a friendlier response for a longer critical section.
	 *
	 * <p>The announcement is made once, at the end, against the status this method is about to
	 * return, rather than in each branch that produced one. {@link LifecycleHints} decides
	 * which statuses are worth telling a browser about, so a branch cannot forget to announce
	 * and {@code WAIT} needs no special case here.
	 *
	 * @return {@code PENDING} while any Check is still outstanding, otherwise the terminal
	 *         status this Verdict moved the Transfer to
	 * @throws UnknownTransferException     if no Transfer has that ID
	 * @throws TransferNotPendingException  if the Transfer has already reached a terminal
	 *                                      status
	 * @throws hu.bankmonitor.payments.transfers.checks.CheckNotRequiredException if the
	 *                                      Transfer's ledger has no row for that Check
	 */
	@Transactional
	public TransferStatus recordVerdict(long transferId, Check check, Verdict verdict) {
		Transfer transfer = transfers.findAndLockById(transferId)
				.orElseThrow(() -> new UnknownTransferException(transferId));
		if (transfer.getStatus() != TransferStatus.PENDING) {
			throw new TransferNotPendingException(transferId, transfer.getStatus());
		}

		LedgerDecision decision = ledger.record(transferId, check, verdict);
		TransferStatus status = switch (decision) {
			case SETTLE -> settle(transfer);
			case REJECT -> reject(transfer);
			case WAIT -> TransferStatus.PENDING;
		};

		hints.announce(transferId, status);
		return status;
	}

	/**
	 * The money leaves: the source Account's balance and Reserved Amount both fall, and the
	 * destination's balance rises. {@link hu.bankmonitor.payments.accounts.Account#settle}
	 * does both halves of the debit, which is what stops a settled Transfer leaving its
	 * reservation behind for every later overdraft check to test against.
	 */
	private TransferStatus settle(Transfer transfer) {
		LockedAccounts locked = lock(transfer);
		claim(transfer, TransferStatus.SETTLED);

		locked.source().settle(transfer.getDebitedAmount());
		locked.destination().credit(transfer.getCreditedAmount());
		return TransferStatus.SETTLED;
	}

	/**
	 * The reservation is given up and no money ever moved, so there is no compensating
	 * movement to write. That is the payoff of settling asynchronously rather than inside the
	 * request: "undo a payment" is "do not make one".
	 */
	private TransferStatus reject(Transfer transfer) {
		LockedAccounts locked = lock(transfer);
		claim(transfer, TransferStatus.REJECTED);

		locked.source().release(transfer.getDebitedAmount());
		return TransferStatus.REJECTED;
	}

	private LockedAccounts lock(Transfer transfer) {
		return accounts.lockForTransfer(transfer.getSourceAccountId(), transfer.getDestinationAccountId());
	}

	/**
	 * Takes the transition before acting on it, so that no money moves against a Transfer this
	 * caller did not move.
	 *
	 * <p>Under the row lock taken at the top of {@link #recordVerdict} the guard cannot fail,
	 * and that is the point of writing it as a guarded update anyway: a future caller that
	 * reached this without the lock fails loudly here instead of settling a Transfer somebody
	 * else has already settled.
	 */
	private void claim(Transfer transfer, TransferStatus terminalStatus) {
		if (transfers.advanceFromPending(transfer.getId(), terminalStatus) != 1) {
			throw new IllegalStateException(
					"transfer %d was advanced by someone else".formatted(transfer.getId()));
		}
	}
}
