package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.testsupport.CapturingStatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;

import static hu.bankmonitor.payments.common.Currency.EUR;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ordering claim at the centre of the ticket, asserted directly rather than inferred from
 * how a race turned out: <b>the Available Balance is read from a row that is already
 * locked.</b>
 *
 * <p>The check itself issues no SQL — it compares two fields of an Account already in memory
 * — so it is not the check that a statement log can find. What it can find is the read that
 * put the Account there, and that is the same claim from the other side: if every statement
 * that reads an Account in the course of a reservation is a {@code select … for update}, then
 * the figures the check tests against cannot have come from an unlocked row.
 *
 * <p>The defect this is aimed at is a plausible one rather than an imagined one. A validation
 * added ahead of the lock — "does the source Account have the funds, so we can answer 422
 * early?" — reads correctly, tests green single-threaded, and quietly reintroduces exactly
 * the window design decision 6 exists to close. The concurrency tests would not catch it
 * either, because the real check still runs under the lock afterwards.
 *
 * <p>{@link ConcurrentReservationsHoldTheBalanceTest} is the consequence of the ordering and
 * this is the ordering itself, which is the same division of labour ticket 12 drew between
 * {@code AccountLockIsASelectForUpdateTest} and {@code AccountsLockInAscendingIdOrderTest}.
 */
@Import(CapturingStatementInspector.class)
class TheBalanceCheckHappensUnderTheLockTest extends ReservationScenario {

	private static final long SOURCE = 1L;
	private static final long DESTINATION = 2L;

	@Autowired
	private CapturingStatementInspector statements;

	@BeforeEach
	void openBothAccountsWithoutRecordingIt() {
		openAccount(SOURCE, 250_00L, EUR);
		openAccount(DESTINATION, 0L, EUR);
		statements.forget();
	}

	@Test
	@DisplayName("every Account a reservation reads is read under a row lock")
	void readsNoAccountOutsideTheLock() {
		reservation.reserve(transferOf(80_00L, SOURCE, DESTINATION));

		assertThat(accountReads())
				.as("both Accounts are read, and neither without a lock")
				.hasSize(2)
				.allSatisfy(sql -> assertThat(sql).endsWith("for update"));
	}

	/**
	 * Both locks are taken before either row is written, so there is no moment where this
	 * transaction has committed to something while still holding fewer locks than it needs.
	 */
	@Test
	@DisplayName("nothing is written until both Accounts are locked")
	void writesNothingBeforeBothLocksAreHeld() {
		reservation.reserve(transferOf(80_00L, SOURCE, DESTINATION));

		List<String> issued = statements.captured();

		assertThat(lastIndexOf(issued, "for update"))
				.as("the writes follow the locks")
				.isLessThan(firstWriteIn(issued));
	}

	private List<String> accountReads() {
		return statements.captured().stream().filter(sql -> sql.contains("from accounts")).toList();
	}

	private static int lastIndexOf(List<String> statements, String fragment) {
		return statements.stream()
				.filter(sql -> sql.contains(fragment))
				.map(statements::lastIndexOf)
				.reduce((earlier, later) -> later)
				.orElseThrow(() -> new AssertionError("no statement contained '" + fragment + "'"));
	}

	private static int firstWriteIn(List<String> statements) {
		return statements.stream()
				.filter(sql -> sql.startsWith("insert") || sql.startsWith("update"))
				.findFirst()
				.map(statements::indexOf)
				.orElseThrow(() -> new AssertionError("the reservation wrote nothing"));
	}
}
