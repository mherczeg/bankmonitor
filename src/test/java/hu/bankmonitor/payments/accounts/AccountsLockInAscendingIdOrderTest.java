package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ordering rule of design decision 6, asserted where it is decidable: which Account
 * {@link AccountLocking} asks for first, and that the answer never depends on which one
 * the Transfer is moving money out of.
 *
 * <p>{@link AccountLocking}'s own Javadoc states the deadlock the rule prevents, and it is
 * not restated here. What matters for these tests is that the property asserted below — the
 * lower ID first, whichever role it holds — is the entire premise that argument stands on.
 * Ticket 13 is where two real threads demonstrate the conclusion; this is what is provable
 * without them.
 *
 * <p>The repository is a recorder rather than a database, because the order two
 * {@code select … for update} statements were issued in is not something the statements
 * themselves say — they are the same SQL with a different bound parameter.
 * {@link AccountLockIsASelectForUpdateTest} is the half that needs a real database.
 */
class AccountsLockInAscendingIdOrderTest {

	private static final long LOWER_ID = 5L;
	private static final long HIGHER_ID = 9L;

	private final RecordingAccountRepository accounts = new RecordingAccountRepository()
			.holding(LOWER_ID, new Money(100_00L, Currency.EUR))
			.holding(HIGHER_ID, new Money(250_00L, Currency.USD));

	private final AccountLocking locking = new AccountLocking(accounts);

	@Test
	@DisplayName("the lower Account ID is locked first when it is the source")
	void locksTheLowerIdFirstWhenItIsTheSource() {
		locking.lockForTransfer(LOWER_ID, HIGHER_ID);

		assertThat(accounts.lockedInOrder).containsExactly(LOWER_ID, HIGHER_ID);
	}

	/** The same order, from the request a role-ordered acquisition would reverse. */
	@Test
	@DisplayName("the lower Account ID is locked first when it is the destination")
	void locksTheLowerIdFirstWhenItIsTheDestination() {
		locking.lockForTransfer(HIGHER_ID, LOWER_ID);

		assertThat(accounts.lockedInOrder).containsExactly(LOWER_ID, HIGHER_ID);
	}

	/**
	 * Acquisition order is not the caller's problem: the pair comes back named by role, so
	 * nothing downstream has to remember which of the two the lock order happened to put
	 * first.
	 */
	@Test
	@DisplayName("the locked Accounts come back named by their role in the Transfer")
	void handsBackTheAccountsByTheirRoleInTheTransfer() {
		LockedAccounts locked = locking.lockForTransfer(HIGHER_ID, LOWER_ID);

		assertThat(locked.source().getCurrency()).isEqualTo(Currency.USD);
		assertThat(locked.destination().getCurrency()).isEqualTo(Currency.EUR);
	}

	/**
	 * Design decision 6 refuses a self-Transfer with a {@code 422} before locking, and
	 * ticket 14 owns that answer. This is the backstop under it: a caller that skipped the
	 * refusal gets an error rather than a pair whose two sides are one row.
	 */
	@Test
	@DisplayName("an Account on both sides of the Transfer is refused rather than locked twice")
	void refusesATransferWithOneAccountOnBothSides() {
		assertThatThrownBy(() -> locking.lockForTransfer(LOWER_ID, LOWER_ID))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(String.valueOf(LOWER_ID));

		assertThat(accounts.lockedInOrder).isEmpty();
	}

	@Test
	@DisplayName("an Account that is not there is reported by ID")
	void namesTheAccountItCouldNotFind() {
		long missingId = 404L;

		assertThatThrownBy(() -> locking.lockForTransfer(LOWER_ID, missingId))
				.isInstanceOf(UnknownAccountException.class)
				.extracting(thrown -> ((UnknownAccountException) thrown).getAccountId())
				.isEqualTo(missingId);
	}

	/**
	 * Records the order it is asked to lock in, which is the one thing a database cannot
	 * report: both statements are the same SQL with a different bound parameter.
	 *
	 * <p>The three refusals below are the read and write paths tickets 09 and 10 added to
	 * {@link AccountRepository}. Reaching one of them from {@link AccountLocking} would be
	 * a bug — the locked path has no business listing or saving — so failing loudly is the
	 * useful answer, and a silent stub would let that bug pass this test.
	 */
	private static final class RecordingAccountRepository implements AccountRepository {

		private final List<Long> lockedInOrder = new ArrayList<>();
		private final Map<Long, Account> rows = new HashMap<>();

		private RecordingAccountRepository holding(long accountId, Money openingBalance) {
			rows.put(accountId, new Account(openingBalance));
			return this;
		}

		@Override
		public Optional<Account> findAndLockById(long accountId) {
			lockedInOrder.add(accountId);
			return Optional.ofNullable(rows.get(accountId));
		}

		@Override
		public List<Account> findAllByOrderByIdAsc() {
			throw new UnsupportedOperationException("the locked path does not list accounts");
		}

		@Override
		public Account save(Account account) {
			throw new UnsupportedOperationException("the locked path does not open accounts");
		}

		@Override
		public List<Account> saveAll(Iterable<Account> accounts) {
			throw new UnsupportedOperationException("the locked path does not seed accounts");
		}
	}
}
