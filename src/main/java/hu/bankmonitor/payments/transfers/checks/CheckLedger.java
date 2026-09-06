package hu.bankmonitor.payments.transfers.checks;

import hu.bankmonitor.payments.transfers.Transfer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The per-Transfer record of which Checks it requires and how each has been answered, and
 * the three operations this package exports: opening that record, answering one Check in it,
 * and reporting what it holds.
 *
 * <p>The first two are the ledger's whole life and they are deliberately the only ways into
 * it. Every row a Transfer will ever have is written by {@link #openFor}, and the only thing
 * that happens to one afterwards is {@link #record} filling in its Verdict — there is no way
 * to add a row, remove one, or answer the same Check twice, which is what lets
 * {@link LedgerDecision#decide} read the rows as the whole truth about the Transfer.
 *
 * <p>{@link #stateOf} is the third and writes nothing. It exists because the ledger was
 * always the answer to "why is this Transfer still pending" and, until ticket 22, was the
 * answer nobody outside this package could ask for. What it hands back is {@link CheckState}
 * rather than the rows, so reading the ledger stays a strictly weaker thing than holding it.
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

	/**
	 * Answers one outstanding Check and says what the whole ledger then supports, in the
	 * caller's transaction.
	 *
	 * <p>The answer is written by {@linkplain CheckLedgerRepository#answer a guarded update}
	 * rather than a read followed by a write, so two deliveries of the same Verdict leave one
	 * answer. A second delivery matching no row is not a refusal: nothing went wrong on the
	 * reporting side, and the caller still needs the decision back, because the delivery that
	 * did win may have been lost on the way home. What it does mean is that the decision here
	 * is made over what the ledger <em>holds</em>, never over the Verdict just handed in.
	 *
	 * <p><b>The rows are read after the update, and nothing may have read them before it.</b>
	 * A bulk update goes straight to the database and leaves the persistence context alone,
	 * so an entry loaded earlier in the same transaction would come back from
	 * {@link CheckLedgerRepository#findAllByTransferIdOrderByRequiredCheck} still carrying its
	 * old, unanswered Verdict — and a ledger whose last outstanding row looks outstanding
	 * decides {@code WAIT}, leaving a fully approved Transfer pending for ever. Nothing on this
	 * path reads the rows before this line does.
	 *
	 * <p>{@link Propagation#MANDATORY} for {@link #openFor}'s reason turned around: the
	 * decision returned here is acted on by moving money, and an answer committed separately
	 * from the movement it authorises is either a settled Transfer nobody paid or a payment
	 * against a ledger that does not record why.
	 *
	 * @return what the Transfer's ledger now supports, which the caller owns acting on
	 * @throws org.springframework.transaction.IllegalTransactionStateException if the caller
	 *                                                                         has no
	 *                                                                         transaction
	 *                                                                         open
	 * @throws CheckNotRequiredException if the Transfer's ledger has no row for that Check,
	 *                                   which is also the answer a Transfer with no ledger at
	 *                                   all gets — it reaches this refusal before
	 *                                   {@link LedgerDecision#decide} can be handed the empty
	 *                                   rows it exists to refuse
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public LedgerDecision record(long transferId, Check check, Verdict verdict) {
		int answered = entries.answer(transferId, check, verdict);
		List<CheckLedgerEntry> ledger = entries.findAllByTransferIdOrderByRequiredCheck(transferId);

		if (answered == 0 && ledger.stream().noneMatch(entry -> entry.isFor(check))) {
			throw new CheckNotRequiredException(transferId, check);
		}
		return LedgerDecision.decide(ledger);
	}

	/**
	 * One Transfer's ledger as something to report: every Check it requires, each with its
	 * Verdict or without one, in the order {@link CheckLedgerRepository} fixes.
	 *
	 * <p>The third and last thing this package exports, and the only one that decides nothing.
	 * It answers "what is this Transfer waiting on", which is the question the single-Transfer
	 * response exists to answer and the one the ledger has always been able to answer without
	 * anyone being able to ask it.
	 *
	 * <p>{@link Propagation#MANDATORY} on a read, for a reason the writes do not have: the
	 * caller is reading a Transfer as well, and the two have to be one snapshot. A Verdict
	 * committing between the Transfer read and this one would produce a report of a settled
	 * Transfer still waiting on a Check — an answer that is wrong in the one place somebody
	 * goes to find out which it is.
	 *
	 * <p><b>A Transfer with no rows is reported as having none, rather than refused.</b>
	 * {@link LedgerDecision#decide} refuses that ledger because it is about to move money on
	 * what the rows say, and no rows would mean moving it unchecked. Nothing is decided here,
	 * and refusing would make the one screen that could show an operator a Transfer nothing
	 * will ever settle the one screen that will not open.
	 *
	 * @return one entry per Check the Transfer requires, empty only for a Transfer that never
	 *         had a ledger or never existed — this read cannot tell those apart, and the caller
	 *         is the half that can, because it has already loaded the Transfer
	 * @throws org.springframework.transaction.IllegalTransactionStateException if the caller
	 *                                                                         has no
	 *                                                                         transaction
	 *                                                                         open
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<CheckState> stateOf(long transferId) {
		return entries.findAllByTransferIdOrderByRequiredCheck(transferId).stream()
				.map(CheckLedgerEntry::state)
				.toList();
	}
}
