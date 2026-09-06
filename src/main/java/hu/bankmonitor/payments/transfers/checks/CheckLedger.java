package hu.bankmonitor.payments.transfers.checks;

import hu.bankmonitor.payments.transfers.Transfer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;

/**
 * The per-Transfer record of which Checks it requires and how each has been answered, and
 * the one operation this package exports today: opening that record.
 *
 * <p>The whole of what the Check Ledger is for rests on it being written <em>with</em> the
 * Transfer. A Transfer whose rows were written afterwards has a window in which it is
 * {@code PENDING} against an empty ledger — which is a Transfer nothing will ever settle,
 * expire or explain, holding an operator's funds until somebody notices. {@link
 * Propagation#MANDATORY} is that requirement stated to the container rather than left as a
 * convention for the caller to honour: this method cannot start a transaction of its own,
 * so the only way to call it is from inside one that is already writing the Transfer.
 *
 * <p>Being late is one way to that state and requiring nothing is the other: a policy that
 * names no Check makes {@link #openFor} write no rows and return, so the caller commits the
 * same stuck Transfer with every test still green. The refusal below is that second way
 * closed, at the only place that can see it happening.
 *
 * <p>The method is public on a package-private-by-default design because {@code
 * @Transactional} on a non-public method is silently ignored under proxy-based AOP — the
 * trap {@link hu.bankmonitor.payments} documents. The class is public for the ordinary
 * reason: {@code FundsReservation} is in the parent package and has to name it.
 */
@Component
public class CheckLedger {

	private final CheckPolicy policy;

	private final CheckLedgerRepository entries;

	CheckLedger(CheckPolicy policy, CheckLedgerRepository entries) {
		this.policy = policy;
		this.entries = entries;
	}

	/**
	 * Writes one outstanding row per Check the Transfer requires, in the caller's
	 * transaction.
	 *
	 * @param transfer a Transfer already written, so that the rows have an ID to point at
	 * @throws org.springframework.transaction.IllegalTransactionStateException if the caller
	 *                                                                         has no
	 *                                                                         transaction
	 *                                                                         open
	 * @throws IllegalStateException if the policy requires no Checks at all, which would
	 *                               write no rows and leave the caller committing the stuck
	 *                               Transfer described above
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void openFor(Transfer transfer) {
		Long transferId = Objects.requireNonNull(transfer.getId(), "a ledger belongs to a written transfer");

		Set<Check> required = policy.requiredFor(transfer);
		if (required.isEmpty()) {
			throw new IllegalStateException("a transfer's check ledger is never empty");
		}

		for (Check check : required) {
			entries.save(CheckLedgerEntry.outstanding(transferId, check));
		}
	}
}
