package hu.bankmonitor.payments.transfers;

import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Persistence for {@link Transfer}, package-private so that reaching it from another slice
 * does not compile (design decision 30).
 *
 * <p>The bare {@link Repository} marker rather than {@code JpaRepository}, on
 * {@link hu.bankmonitor.payments.accounts.Account}'s precedent: it declares nothing, so
 * every method here is one with a call site today. The queries this design calls for — the
 * status filter, the newest-first listing, the reaper's overdue scan and the conditional
 * update each transition needs — arrive with the ticket that has a caller to shape them.
 */
interface TransferRepository extends Repository<Transfer, Long> {

	/** Writes one Transfer, which is how a Transfer comes to exist. */
	Transfer save(Transfer transfer);

	/** One Transfer by ID, empty when no such Transfer was ever requested. */
	Optional<Transfer> findById(Long id);
}
