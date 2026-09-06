package hu.bankmonitor.payments.transfers.checks;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;

/**
 * One Check a Transfer requires, and how that Check has answered it.
 *
 * <p>A Transfer's rows together are its Check Ledger, and they are written when the
 * Transfer is: the set of rows is fixed at request time, not a log that grows as parties
 * report things. The unique constraint on {@code (transfer_id, required_check)}
 * says the same in the table, and it is what will make a Check service's at-least-once
 * delivery safe to receive.
 *
 * <p><b>An unanswered Check has no Verdict, and the column is null.</b> The alternative —
 * a third {@code PENDING} constant on {@link Verdict}, which is the shape design decision
 * 8 sketched — would make "nobody has answered" one of the answers, and every reader would
 * then have to know which of the three is not really a Verdict. {@link
 * hu.bankmonitor.payments.transfers.TransferStatus} carries {@code PENDING} for the
 * opposite reason: a Transfer really is in that state. Nothing is in the state of having
 * given the null answer.
 *
 * <p><b>The Transfer is referenced by ID, not by association</b>, on {@code Transfer}'s own
 * precedent: a Transfer only ever leaves {@code PENDING} by a conditional update guarded on
 * the status it is leaving, and an association would offer a second way to reach one that
 * quietly skips that.
 *
 * <p><b>The identifier is surrogate rather than the pair that is unique</b>, on {@code
 * IdempotencyRecord}'s precedent: Spring Data decides between insert and merge from whether
 * the identifier is set, so an assigned key would turn every {@code save} into a
 * {@code SELECT} followed by an {@code UPDATE}. The uniqueness that matters is a named
 * constraint either way.
 *
 * <p>There is deliberately no mutator. Ticket 20 writes a Verdict as an update guarded on
 * the row still being unanswered, because two deliveries of the same Verdict have to leave
 * the Transfer advanced once; a setter would offer a second way to make that transition
 * that quietly is not atomic.
 */
@Entity
@Table(name = "check_ledger")
class CheckLedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long transferId;

	// `check` is reserved in SQL, so the column this name produces is `required_check`.
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private Check requiredCheck;

	// If left unannotated, JPA stores the verdict by ordinal. See design decision 29.
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private Verdict verdict;

	protected CheckLedgerEntry() {
		// required by JPA
	}

	/**
	 * A row in any state it can hold, which is what {@link LedgerDecision#decide} is
	 * specified over and therefore has to be able to see.
	 *
	 * <p>Production never reaches this with a Verdict in hand — {@link #outstanding} is how
	 * a row comes into being, and the answer arrives later as an update. That is the
	 * distinction being drawn: constructing a row in a state is not the same operation as
	 * <em>transitioning</em> one into it, and only the transition has a race to lose.
	 */
	CheckLedgerEntry(Long transferId, Check requiredCheck, Verdict verdict) {
		this.transferId = Objects.requireNonNull(transferId, "a check is required of a transfer");
		this.requiredCheck = Objects.requireNonNull(requiredCheck, "a ledger row is one check");
		this.verdict = verdict;
	}

	/** Opens a Check on a Transfer with nobody having answered it yet. */
	static CheckLedgerEntry outstanding(Long transferId, Check requiredCheck) {
		return new CheckLedgerEntry(transferId, requiredCheck, null);
	}

	Long getId() {
		return id;
	}

	/** Whether this Check has said no — the one answer that ends a Transfer on its own. */
	boolean isRejected() {
		return verdict == Verdict.REJECTED;
	}

	/** Whether anyone has answered this Check at all, either way. */
	boolean isAnswered() {
		return verdict != null;
	}
}
