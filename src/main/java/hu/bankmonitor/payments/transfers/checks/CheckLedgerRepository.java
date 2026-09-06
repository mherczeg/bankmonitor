package hu.bankmonitor.payments.transfers.checks;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Persistence for {@link CheckLedgerEntry}, package-private so that reaching it from
 * another slice does not compile (design decision 30).
 *
 * <p>The bare {@link Repository} marker rather than {@code JpaRepository}, on {@code
 * Account}'s precedent: it declares nothing, so every method here is one with a call site
 * today. The two ticket 20 brought — the read that a Verdict's decision is made over, and
 * the update guarded on the row still being unanswered — are below.
 */
interface CheckLedgerRepository extends Repository<CheckLedgerEntry, Long> {

	/** Opens one Check on one Transfer, which is how a ledger row comes to exist. */
	CheckLedgerEntry save(CheckLedgerEntry entry);

	/**
	 * One Transfer's whole Check Ledger: what {@link LedgerDecision#decide} is handed, and what
	 * {@link CheckLedger#stateOf} reports.
	 *
	 * <p><b>Ordered by the Check, for the reader rather than for the decision.</b> {@code
	 * decide} is specified over the rows in any order and says so; the reporting path is not,
	 * because a Check Ledger that reshuffles itself between two refetches of unchanged data is
	 * a screen an operator cannot read. Two queries differing only in an {@code ORDER BY} would
	 * be the alternative, and an ordering the settlement path does not need is cheaper than a
	 * second method the next reader has to choose between.
	 *
	 * <p>The Check is a total order on its own here: {@code check_ledger_one_row_per_check}
	 * makes one Check at most one row of a Transfer's ledger, so there is no tie to break. The
	 * column stores the constant's name, so the order is alphabetical and not the policy's —
	 * what is promised to a reader is stability, not a running order, and a Check added to
	 * {@code Check} sorts wherever its name falls.
	 */
	List<CheckLedgerEntry> findAllByTransferIdOrderByRequiredCheck(Long transferId);

	/**
	 * Writes a Verdict into the one row that is still outstanding for that Check, and
	 * reports how many rows that was — one, or none because somebody has already answered.
	 *
	 * <p><b>{@code verdict is null} is the guard, and it is what makes a Check service's
	 * at-least-once delivery safe.</b> A second delivery of the same Verdict matches no row
	 * and writes nothing, so the ledger keeps the answer it already had; a contradicting one
	 * cannot overwrite it either. A read-then-write would be the same rule with a window in
	 * the middle where both deliveries see the row unanswered.
	 *
	 * <p>A bulk update rather than a mutator on the entity, on {@code IdempotencyRecord}'s
	 * precedent: a setter would offer a second way to make this transition that quietly is
	 * not atomic. {@link CheckLedgerEntry} therefore has none.
	 */
	@Modifying
	@Query("""
			UPDATE CheckLedgerEntry entry SET entry.verdict = :verdict
			WHERE entry.transferId = :transferId
			  AND entry.requiredCheck = :check
			  AND entry.verdict IS NULL
			""")
	int answer(Long transferId, Check check, Verdict verdict);
}
