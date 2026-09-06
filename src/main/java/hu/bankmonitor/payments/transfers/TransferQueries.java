package hu.bankmonitor.payments.transfers;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Reading {@link Transfer} rows back, package-private on {@link TransferRepository}'s
 * reasoning (design decision 30).
 *
 * <p>A second interface over the same entity rather than more methods on
 * {@code TransferRepository}, because that repository carries {@code save} and nothing but
 * {@link FundsReservation} may hold it —  {@code NothingButTheReservationCreatesATransferTest}
 * forbids the dependency itself, on the grounds that a class holding the repository is one
 * edit away from writing a {@code PENDING} Transfer against an empty check ledger. Splitting
 * the read side off keeps that rule at full strength while a read side exists: a reader
 * cannot call {@code save} because it cannot name it. Every later read — the reaper's
 * overdue scan among them — belongs here for the same reason.
 *
 * <p>The bare {@link Repository} marker rather than {@code JpaRepository}, as elsewhere in
 * this codebase: it declares nothing, so every method here is one with a call site today.
 */
interface TransferQueries extends Repository<Transfer, Long> {

	/** One Transfer by ID, empty when no such Transfer was ever requested. */
	Optional<Transfer> findById(Long id);

	/**
	 * Every Transfer, newest first, with the identifier breaking a tie so that the order is
	 * total.
	 *
	 * <p>Both halves of that order are part of the listing's contract rather than details of
	 * the query. Requested-time descending is what the Transactions screen is for; the tie
	 * break is what stops it reshuffling itself between two refetches of unchanged data, and
	 * two Transfers sharing an instant is not a contrivance — {@code created_at} is stored to
	 * microseconds and nothing spaces requests out. It is also the key
	 * {@code docs/deferred.md} names for the cursor pagination this listing does not yet have.
	 */
	List<Transfer> findAllByOrderByCreatedAtDescIdDesc();

	/** The same listing narrowed to one status, in the same order, for the {@code ?status=} filter. */
	List<Transfer> findAllByStatusOrderByCreatedAtDescIdDesc(TransferStatus status);
}
