package hu.bankmonitor.payments.transfers.checks;

import org.springframework.data.repository.Repository;

/**
 * Persistence for {@link CheckLedgerEntry}, package-private so that reaching it from
 * another slice does not compile (design decision 30).
 *
 * <p>The bare {@link Repository} marker rather than {@code JpaRepository}, on {@code
 * Account}'s precedent: it declares nothing, so every method here is one with a call site
 * today. The two this design will need — the read that a Verdict's decision is made over,
 * and the update guarded on the row still being unanswered — arrive with ticket 20, which
 * is the first thing with a question to ask of them.
 */
interface CheckLedgerRepository extends Repository<CheckLedgerEntry, Long> {

	/** Opens one Check on one Transfer, which is how a ledger row comes to exist. */
	CheckLedgerEntry save(CheckLedgerEntry entry);
}
