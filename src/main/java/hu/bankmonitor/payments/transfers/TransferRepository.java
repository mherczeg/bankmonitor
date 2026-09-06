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
 * back.
 *
 * <p>Ticket 15 expected the conditional update each transition needs to arrive here too, on
 * the grounds that it is a write. Ticket 20 brought it and it could not: the caller is
 * {@link VerdictRecording}, and a class holding this interface to advance a Transfer would
 * hold {@code save} along with it — the one thing the rule refuses. What matters is not that
 * a method writes but that {@code save} is reachable, so the transition went to
 * {@link TransferTransitions} and this interface still declares one method, which is what
 * lets the rule name it outright.
 *
 * <p>The bare {@link Repository} marker rather than {@code JpaRepository}, on
 * {@link hu.bankmonitor.payments.accounts.Account}'s precedent: it declares nothing, so
 * every method here is one with a call site today.
 */
interface TransferRepository extends Repository<Transfer, Long> {

	/** Writes one Transfer, which is how a Transfer comes to exist. */
	Transfer save(Transfer transfer);
}
