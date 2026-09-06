package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.transfers.checks.CheckLedger;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reading Transfers back: all of them, one status of them, or one of them.
 *
 * <p>It sits beside {@link FundsReservation} rather than inside it because the two share
 * nothing but a table. {@code FundsReservation} is one operation whose correctness is an
 * ordering of locks and writes; this is the read side, and folding it in would put
 * {@code readOnly} reads inside the class whose Javadoc is entirely about what must not
 * happen between a lock and a commit.
 *
 * <p>Design decision 30 forbids a controller holding a repository, so the read goes through
 * here — where the transaction begins — as {@code AccountService}'s listing does. What it
 * holds is {@link TransferQueries} rather than {@link TransferRepository}: the write side
 * belongs to {@link FundsReservation} alone, and that interface's own Javadoc says why the
 * two are separate.
 *
 * <p><b>Both methods answer with {@link TransferResponse} rather than with the entity</b>,
 * which ticket 22 settled by making one of them have to. The single-Transfer response is
 * assembled from two sources that must be read in one transaction, and the transaction is
 * here — so the assembly is here, and a controller cannot be handed the halves and asked to
 * put them together after the transaction it needed has closed. Once one read reports the
 * representation, both do: a class whose two methods hand back different kinds of thing is
 * where the next reader guesses wrong.
 *
 * <p>The class is package-private and the methods are public, which looks backwards and is
 * not: proxy-based AOP silently ignores {@code @Transactional} on a non-public method, so a
 * demotion here would leave a method that reads as transactional and is not, with no error
 * to read.
 *
 * <p>Nothing here takes a lock. A Transfer is read to be reported, never to be decided
 * against — every transition is a conditional update guarded on the status it is leaving
 * (design decision 11) — so these reads cannot be the stale half of a read-then-write.
 */
@Service
class TransferLookup {

	private final TransferQueries transfers;

	private final CheckLedger ledger;

	TransferLookup(TransferQueries transfers, CheckLedger ledger) {
		this.transfers = transfers;
		this.ledger = ledger;
	}

	/**
	 * Every Transfer, or every Transfer in one status when a status is given. Design decision
	 * 31 declines pagination, so this is all of them either way.
	 *
	 * <p>The two queries are separate methods on {@link TransferQueries} rather than one
	 * query with an optional predicate: a derived query cannot express "match anything" for a
	 * null, and a {@code (:status is null or t.status = :status)} JPQL string would trade a
	 * name that says what it fetches for a predicate the database has to evaluate per row.
	 *
	 * @param status the one status to narrow to, or {@code null} for every status — which is
	 *               design decision 19's reading of the requirement, not a convenience: a
	 *               {@code PENDING} Transfer that appeared on no list would make the only list
	 *               screen misleading
	 */
	@Transactional(readOnly = true)
	public List<TransferResponse> list(@Nullable TransferStatus status) {
		List<Transfer> found = status == null
				? transfers.findAllByOrderByCreatedAtDescIdDesc()
				: transfers.findAllByStatusOrderByCreatedAtDescIdDesc(status);
		return found.stream().map(TransferResponse::of).toList();
	}

	/**
	 * One Transfer by the identifier that addresses it, with the Check Ledger it is waiting on.
	 *
	 * <p><b>The ledger is read here rather than by the caller because here is where the
	 * transaction is</b>, and the two reads have to be one snapshot. A Verdict committing
	 * between them would answer with a settled Transfer that is still waiting on a Check, in
	 * the one response somebody fetches to find out which of those is true.
	 * {@link CheckLedger#stateOf} states that requirement to the container rather than leaving
	 * it to this method to honour: it cannot start a transaction of its own, so a second caller
	 * that read the ledger on its way to a response would fail rather than answer.
	 *
	 * @throws UnknownTransferException if no Transfer has that identifier
	 */
	@Transactional(readOnly = true)
	public TransferResponse byId(long transferId) {
		Transfer transfer = transfers.findById(transferId)
				.orElseThrow(() -> new UnknownTransferException(transferId));
		return TransferResponse.withChecks(transfer, ledger.stateOf(transferId));
	}
}
