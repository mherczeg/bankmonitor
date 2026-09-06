package hu.bankmonitor.payments.transfers;

import org.springframework.data.repository.Repository;

/**
 * The write side of {@link Transfer} persistence, package-private so that reaching it from
 * another slice does not compile (design decision 30).
 *
 * <p>Only {@link FundsReservation} may hold this interface, and
 * {@code NothingButTheReservationCreatesATransferTest} enforces that as a dependency rather
 * than as a call. Reads therefore live on {@link TransferQueries}, which anything in the
 * slice may hold: the split is what lets that rule stay a rule now that Transfers are read
 * back. The conditional update each transition needs is a write, so it arrives here, with
 * the ticket that has a caller to shape it.
 *
 * <p>The bare {@link Repository} marker rather than {@code JpaRepository}, on
 * {@link hu.bankmonitor.payments.accounts.Account}'s precedent: it declares nothing, so
 * every method here is one with a call site today.
 */
interface TransferRepository extends Repository<Transfer, Long> {

	/** Writes one Transfer, which is how a Transfer comes to exist. */
	Transfer save(Transfer transfer);
}
