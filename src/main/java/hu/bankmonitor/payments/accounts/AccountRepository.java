package hu.bankmonitor.payments.accounts;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

/**
 * Persistence for {@link Account}, package-private so that reaching it from another slice
 * does not compile (design decision 30).
 *
 * <p>It extends the bare {@link Repository} marker rather than {@code CrudRepository},
 * which declares nothing, so every method here has a call site today. {@code CrudRepository}
 * would hand the slice {@code delete}, {@code deleteAll} and a lookup by id before anything
 * asks for them — and a repository whose surface is larger than its use is a set of
 * signatures guessed rather than designed. Ticket 12's ascending-ID locking query arrives
 * with its own.
 */
interface AccountRepository extends Repository<Account, Long> {

	/**
	 * Every Account, oldest first. The order is part of the listing's contract rather than
	 * a detail of the query: without it the accounts screen may reshuffle itself between
	 * two refetches of data that has not changed.
	 */
	List<Account> findAllByOrderByIdAsc();

	/** Writes one Account, which is how an Account comes to exist. */
	Account save(Account account);

	/**
	 * Writes several Accounts in one transaction, which is what the demo seeding of
	 * {@link DemoAccountSeeder} needs and all it needs — a half-seeded application is a
	 * worse starting point than an empty one.
	 */
	List<Account> saveAll(Iterable<Account> accounts);

	/**
	 * Reads one Account and holds a row lock on it until the surrounding transaction ends,
	 * so no other transaction can read-for-update or write that row in the meantime.
	 *
	 * <p>{@link LockModeType#PESSIMISTIC_WRITE} is what turns the {@code select} into a
	 * {@code select … for update}. Call it through {@link AccountLocking} rather than
	 * directly: on its own it says nothing about the order two of them are taken in, and
	 * the order is the entire deadlock argument of design decision 6.
	 */
	@Lock(PESSIMISTIC_WRITE)
	Optional<Account> findAndLockById(long accountId);
}
