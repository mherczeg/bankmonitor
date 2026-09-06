package hu.bankmonitor.payments.transfers;

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

	TransferLookup(TransferQueries transfers) {
		this.transfers = transfers;
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
	public List<Transfer> list(@Nullable TransferStatus status) {
		return status == null
				? transfers.findAllByOrderByCreatedAtDescIdDesc()
				: transfers.findAllByStatusOrderByCreatedAtDescIdDesc(status);
	}

	/**
	 * One Transfer by the identifier that addresses it.
	 *
	 * @throws UnknownTransferException if no Transfer has that identifier
	 */
	@Transactional(readOnly = true)
	public Transfer byId(long transferId) {
		return transfers.findById(transferId)
				.orElseThrow(() -> new UnknownTransferException(transferId));
	}
}
