package hu.bankmonitor.payments.transfers;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Optional;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

/**
 * Advancing a {@link Transfer} out of {@code PENDING}: the row lock that serialises the
 * decision and the guarded update that records it, package-private on
 * {@link TransferRepository}'s reasoning (design decision 30).
 *
 * <p>A third interface over one entity, and the third is earned rather than tidy. Writing a
 * Transfer is restricted to {@link FundsReservation} because a Transfer written anywhere else
 * would be {@code PENDING} against an empty Check Ledger; advancing one is not restricted at
 * all, because the guard travels with the statement and a second caller cannot make the same
 * transition twice however it reached the method. Those are different kinds of access, so
 * they are different interfaces — the same reason ticket 15 gave for {@link TransferQueries},
 * applied to the case ticket 15 predicted would go the other way. Putting the update on
 * {@code TransferRepository} would hand {@link VerdictRecording} {@code save} as well, and
 * putting it on {@code TransferQueries} would leave an interface named for reads declaring an
 * {@code UPDATE}.
 *
 * <p>The lock and the update stay together here because they are one argument and reading
 * either alone gets that argument backwards: the lock is what makes concurrent Verdicts safe,
 * and the guard is not.
 *
 * <p>The bare {@link Repository} marker rather than {@code JpaRepository}, as elsewhere in
 * this codebase: it declares nothing, so every method here is one with a call site today. The
 * reaper's overdue scan is a read and belongs on {@code TransferQueries}; the claim it makes
 * on the Transfer it reaps belongs here.
 */
interface TransferTransitions extends Repository<Transfer, Long> {

	/**
	 * Reads one Transfer and holds a row lock on it until the surrounding transaction ends,
	 * so that everything deciding this Transfer's next status is serialised behind it.
	 *
	 * <p><b>This is what makes concurrent Verdicts safe, and the guarded update below is
	 * not.</b> Two Verdicts arriving together each write their own ledger row, and neither
	 * transaction can see the other's until it commits — so both read a ledger with one
	 * Check still outstanding, both decide to wait, and the Transfer stays {@code PENDING}
	 * for ever against a fully approved ledger, holding the operator's funds. No guard on
	 * the {@code UPDATE} helps, because neither transaction ever reaches one. Only taking
	 * this lock before touching the ledger does.
	 *
	 * <p>{@link LockModeType#PESSIMISTIC_WRITE} is what turns the {@code select} into a
	 * {@code select … for update}, exactly as on {@code AccountRepository}. The lock order
	 * for anything that holds both is <b>Transfer first, then Accounts ascending</b>; it is
	 * acyclic against the reservation, which takes Account locks only and never waits on a
	 * Transfer row that already exists.
	 *
	 * <p>Separate from {@link TransferQueries#findById} rather than the same method used
	 * under a lock: {@code @Lock} is a property of the declaration, so one method cannot be
	 * both, and a reader that acquired a write lock by accident would serialise the listing
	 * screen against every settlement.
	 */
	@Lock(PESSIMISTIC_WRITE)
	Optional<Transfer> findAndLockById(Long id);

	/**
	 * Moves a {@code PENDING} Transfer to a terminal status, and reports whether it was this
	 * caller that moved it — one row when the Transfer was still pending, none when it was
	 * not.
	 *
	 * <p>The transition carries its own precondition rather than trusting the caller to have
	 * checked one. Under the row lock above it cannot fail, and that is the point of writing
	 * it this way: a future caller that reached this method without the lock fails loudly
	 * instead of settling a Transfer that somebody else already settled. It is the same
	 * shape as the idempotency record's claim and the expiry reaper's sweep — one idea used
	 * three times, not three mechanisms.
	 *
	 * <p>A bulk update rather than a mutator on the entity, for {@code CheckLedgerEntry}'s
	 * reason: a setter would offer a second way to make this transition that quietly is not
	 * atomic. {@link Transfer} therefore has none.
	 */
	@Modifying
	@Query("""
			UPDATE Transfer transfer SET transfer.status = :terminalStatus
			WHERE transfer.id = :id
			  AND transfer.status = hu.bankmonitor.payments.transfers.TransferStatus.PENDING
			""")
	int advanceFromPending(Long id, TransferStatus terminalStatus);
}
