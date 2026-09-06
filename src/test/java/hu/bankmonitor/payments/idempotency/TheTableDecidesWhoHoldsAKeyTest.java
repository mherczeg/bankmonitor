package hu.bankmonitor.payments.idempotency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who holds an Idempotency Key is settled by the table, and every claim mechanic in this
 * slice is a way of asking it.
 *
 * <p>Two things are under test and they are not separable. One is the SQL: a unique
 * constraint that refuses a second live claim, and an update guarded on the status it is
 * leaving so that exactly one of several retries reclaims a failed key. The other is
 * <em>when each of those writes becomes visible</em>, which is the part that has no
 * meaning inside a single transaction — a claim nobody else can see has serialised
 * nothing, and a failure marked in the transaction that failed is not marked at all.
 *
 * <p>So this test is deliberately <b>not</b> transactional. {@code @DataJpaTest} wraps each
 * method in a transaction and rolls it back, which here would hide every distinction it
 * exists to draw: the claim, the reclaim and the failure marking all run in transactions of
 * their own, and a test that never commits cannot tell that apart from a test that does.
 * The cost is that rows survive the method that wrote them, so {@link #dropEveryClaim()}
 * clears the table by hand.
 *
 * <p>Concurrency itself is ticket 18's, against the running application. What is asserted
 * here is the mechanism those tests rely on, one caller at a time.
 */
@DataJpaTest
@Import(IdempotencyClaims.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties =
		// The application's own setting, restated because the migration is half of every
		// assertion below: `validate` has compared the entity against V3 before any run.
		"spring.jpa.hibernate.ddl-auto=validate")
class TheTableDecidesWhoHoldsAKeyTest {

	private static final String KEY = "6f9619ff-8b86-d011-b42d-00cf4fc964ff";
	private static final String PAYLOAD_HASH = "1".repeat(64);
	private static final String A_DIFFERENT_PAYLOAD_HASH = "2".repeat(64);
	private static final String A_KEY_NOBODY_CLAIMED = "0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9";
	private static final String STORED_RESPONSE = "{\"id\":42,\"status\":\"PENDING\"}";

	@Autowired
	private IdempotencyClaims claims;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private JdbcTemplate jdbc;

	private TransactionTemplate operation;

	@AfterEach
	void dropEveryClaim() {
		jdbc().update("DELETE FROM idempotency_records");
	}

	/**
	 * The connection is opened <em>before</em> the claim and held open across it, so it is
	 * demonstrably not the one the claim used and demonstrably not sharing its transaction.
	 * Reading the row through it is the whole of what "another request can see the claim"
	 * means.
	 */
	@Test
	@DisplayName("a claim is committed before the caller returns, and another connection sees it")
	void aClaimIsCommittedBeforeTheCallerReturns() throws SQLException {
		try (Connection onlooker = dataSource.getConnection()) {
			claims.claim(KEY, PAYLOAD_HASH);

			assertThat(readStatusThrough(onlooker)).isEqualTo("IN_PROGRESS");
		}

		assertThat(storedPayloadHash()).isEqualTo(PAYLOAD_HASH);
		assertThat(storedResponseBody()).isNull();
	}

	/**
	 * The other half of "in a transaction of its own", and the half the test above cannot
	 * see: with no caller's transaction open, {@code REQUIRED} would commit at the same
	 * moment and the two propagations would be indistinguishable. Under a caller that rolls
	 * back they come apart — a claim that had merely joined its caller would vanish with
	 * it, handing the key to somebody else while the first request is still being answered.
	 */
	@Test
	@DisplayName("a claim and a reclaim both outlive a caller's transaction that rolls back")
	void aClaimAndAReclaimBothOutliveACallersTransactionThatRollsBack() {
		assertThatThrownBy(() -> operation().executeWithoutResult(status -> {
			claims.claim(KEY, PAYLOAD_HASH);
			throw new IllegalStateException("the caller never got as far as the work");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(storedStatus()).isEqualTo("IN_PROGRESS");

		claims.markFailed(KEY);

		assertThatThrownBy(() -> operation().executeWithoutResult(status -> {
			claims.reclaimFailed(KEY);
			throw new IllegalStateException("nor did the retry that took the key back");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(storedStatus()).isEqualTo("IN_PROGRESS");
	}

	/**
	 * The second half of the assertion is the one that matters: the surviving row still
	 * carries the <em>first</em> payload hash. A claim implemented as a read followed by a
	 * write would have found the row and updated it, which is silent, plausible, and hands
	 * the key to the second caller while the first is still using it.
	 */
	@Test
	@DisplayName("a second claim of a live key is refused by the constraint, not merged over it")
	void aSecondClaimOfALiveKeyIsRefusedByTheConstraint() {
		claims.claim(KEY, PAYLOAD_HASH);

		assertThatThrownBy(() -> claims.claim(KEY, A_DIFFERENT_PAYLOAD_HASH))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasStackTraceContaining("IDEMPOTENCY_RECORDS_KEY_IS_UNIQUE");

		assertThat(storedPayloadHash()).isEqualTo(PAYLOAD_HASH);
	}

	@Test
	@DisplayName("exactly one of several retries reclaims a failed key")
	void exactlyOneOfSeveralRetriesReclaimsAFailedKey() {
		claims.claim(KEY, PAYLOAD_HASH);
		claims.markFailed(KEY);

		assertThat(claims.reclaimFailed(KEY)).isTrue();
		assertThat(claims.reclaimFailed(KEY)).isFalse();
		assertThat(storedStatus()).isEqualTo("IN_PROGRESS");
	}

	/** The guard, from the other side: a key somebody is still using is not up for grabs. */
	@Test
	@DisplayName("a key that has not failed cannot be reclaimed")
	void aKeyThatHasNotFailedCannotBeReclaimed() {
		claims.claim(KEY, PAYLOAD_HASH);

		assertThat(claims.reclaimFailed(KEY)).isFalse();
		assertThat(storedStatus()).isEqualTo("IN_PROGRESS");
	}

	@Test
	@DisplayName("reclaiming a key nobody ever claimed matches nothing")
	void reclaimingAKeyNobodyEverClaimedMatchesNothing() {
		assertThat(claims.reclaimFailed(KEY)).isFalse();
	}

	/**
	 * The reason {@code markFailed} runs in a transaction of its own, asserted as the
	 * scenario rather than as the annotation: the operation being reported rolls back, and
	 * the row it left behind has to say {@code FAILED} anyway. Joining that transaction
	 * would undo the status write along with everything else and strand the key at
	 * {@code IN_PROGRESS}, where no retry can ever reach it.
	 */
	@Test
	@DisplayName("marking a key failed survives the rollback of the operation it reports")
	void markingAKeyFailedSurvivesTheRollbackOfTheOperationItReports() {
		claims.claim(KEY, PAYLOAD_HASH);

		assertThatThrownBy(() -> operation().executeWithoutResult(status -> {
			claims.markFailed(KEY);
			throw new IllegalStateException("the reservation could not be made");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(storedStatus()).isEqualTo("FAILED");
		assertThat(claims.reclaimFailed(KEY)).isTrue();
	}

	/**
	 * The same scenario with the opposite requirement, which is what makes the pair worth
	 * writing: success is only success if the work committed. Design decision 4 bundles
	 * this write with the money movement precisely so that a rollback takes both, leaving a
	 * key that is still claimed rather than one promising a transfer nobody made.
	 */
	@Test
	@DisplayName("marking a key succeeded is undone by the rollback of the operation it reports")
	void markingAKeySucceededIsUndoneByTheRollbackOfTheOperationItReports() {
		claims.claim(KEY, PAYLOAD_HASH);

		assertThatThrownBy(() -> operation().executeWithoutResult(status -> {
			claims.markSucceeded(KEY, STORED_RESPONSE);
			throw new IllegalStateException("the reservation could not be made");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(storedStatus()).isEqualTo("IN_PROGRESS");
		assertThat(storedResponseBody()).isNull();
	}

	@Test
	@DisplayName("a committed operation leaves the response for a repeat of the key to replay")
	void aCommittedOperationLeavesTheResponseForARepeatOfTheKeyToReplay() {
		claims.claim(KEY, PAYLOAD_HASH);

		operation().executeWithoutResult(status -> claims.markSucceeded(KEY, STORED_RESPONSE));

		assertThat(storedStatus()).isEqualTo("SUCCEEDED");
		assertThat(storedResponseBody()).isEqualTo(STORED_RESPONSE);
		assertThat(claims.reclaimFailed(KEY)).isFalse();
	}

	/**
	 * {@code MANDATORY} turns the one mistake this design cannot survive into an exception
	 * at the call site. Committing the success on its own would look like it worked, and
	 * the divergence — a key reporting a transfer that was rolled back — only shows up
	 * later, on a retry that replays a response for money that never moved.
	 */
	@Test
	@DisplayName("marking a key succeeded outside the money-moving transaction is refused")
	void markingAKeySucceededOutsideTheMoneyMovingTransactionIsRefused() {
		claims.claim(KEY, PAYLOAD_HASH);

		assertThatThrownBy(() -> claims.markSucceeded(KEY, STORED_RESPONSE))
				.isInstanceOf(IllegalTransactionStateException.class);

		assertThat(storedStatus()).isEqualTo("IN_PROGRESS");
	}

	/**
	 * The cost of {@code REQUIRES_NEW}, written down where the next caller will meet it.
	 * A new transaction cannot see the locks the suspended one holds, so reporting a
	 * failure from inside the transaction that already wrote to this row — the obvious
	 * place, a {@code catch} within the operation — blocks on a lock only that transaction
	 * can release, and only once this call returns.
	 *
	 * <p>It waits out the database's lock timeout rather than deadlocking forever, which
	 * is why this method takes a couple of seconds and why the failure arrives as a
	 * timeout rather than as anything naming the real mistake. Worse, the row it leaves
	 * behind is stranded at {@code IN_PROGRESS} — precisely the state {@code markFailed}
	 * exists to prevent — so the symptom is a slow request followed by a key that can
	 * never be retried.
	 */
	@Test
	@DisplayName("marking a key failed from inside the transaction that wrote to it waits on itself")
	void markingAKeyFailedFromInsideTheTransactionThatWroteToItWaitsOnItself() {
		claims.claim(KEY, PAYLOAD_HASH);

		assertThatThrownBy(() -> operation().executeWithoutResult(status -> {
			claims.markSucceeded(KEY, STORED_RESPONSE);
			claims.markFailed(KEY);
		})).isInstanceOf(CannotAcquireLockException.class);

		assertThat(storedStatus()).isEqualTo("IN_PROGRESS");
	}

	/**
	 * Both unguarded updates match on the key alone, so a count of zero cannot mean a race
	 * was lost — it means the key names no row. Left silent, the caller is told the claim
	 * moved while the claim it meant to move sits at {@code IN_PROGRESS} with nothing that
	 * will ever reach it, which is the whole failure this slice is against.
	 */
	@Test
	@DisplayName("marking a key nobody claimed is refused rather than quietly matching nothing")
	void markingAKeyNobodyClaimedIsRefused() {
		assertThatThrownBy(() -> claims.markFailed(KEY))
				.isInstanceOf(EmptyResultDataAccessException.class);

		assertThatThrownBy(() -> operation().executeWithoutResult(
				status -> claims.markSucceeded(KEY, STORED_RESPONSE)))
				.isInstanceOf(EmptyResultDataAccessException.class);
	}

	/**
	 * The same refusal from the direction that actually bites: a key that <em>is</em>
	 * claimed, mistyped at the call site. Nothing about the wrong key is unusual enough for
	 * the database to object to on its own.
	 */
	@Test
	@DisplayName("marking the wrong key leaves the real claim alone and says so")
	void markingTheWrongKeyLeavesTheRealClaimAlone() {
		claims.claim(KEY, PAYLOAD_HASH);

		assertThatThrownBy(() -> claims.markFailed(A_KEY_NOBODY_CLAIMED))
				.isInstanceOf(EmptyResultDataAccessException.class);

		assertThat(storedStatus()).isEqualTo("IN_PROGRESS");
	}

	@Test
	@DisplayName("the table refuses a status the domain has no name for")
	void refusesAStatusTheDomainHasNoNameFor() {
		assertThatThrownBy(() -> insertRecord("ABANDONED", null))
				.hasStackTraceContaining("IDEMPOTENCY_RECORDS_STATUS_IS_KNOWN");
	}

	/** Both directions of one rule: a response means finished, and finished means a response. */
	@Test
	@DisplayName("the table refuses a stored response that disagrees with the status")
	void refusesAResponseThatDisagreesWithTheStatus() {
		assertThatThrownBy(() -> insertRecord("IN_PROGRESS", STORED_RESPONSE))
				.hasStackTraceContaining("IDEMPOTENCY_RECORDS_RESPONSE_ONLY_WHEN_SUCCEEDED");
		assertThatThrownBy(() -> insertRecord("SUCCEEDED", null))
				.hasStackTraceContaining("IDEMPOTENCY_RECORDS_RESPONSE_ONLY_WHEN_SUCCEEDED");
		assertThatCode(() -> insertRecord("SUCCEEDED", STORED_RESPONSE)).doesNotThrowAnyException();
	}

	private String readStatusThrough(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet row = statement.executeQuery(
						"SELECT status FROM idempotency_records WHERE idempotency_key = '" + KEY + "'")) {
			return row.next() ? row.getString(1) : null;
		}
	}

	private String storedStatus() {
		return readColumn("status");
	}

	private String storedPayloadHash() {
		return readColumn("payload_hash");
	}

	private String storedResponseBody() {
		return readColumn("response_body");
	}

	private String readColumn(String column) {
		return jdbc().queryForObject(
				"SELECT " + column + " FROM idempotency_records WHERE idempotency_key = ?",
				String.class, KEY);
	}

	private void insertRecord(String status, String responseBody) {
		jdbc().update("""
				INSERT INTO idempotency_records (idempotency_key, payload_hash, status, response_body)
				VALUES (?, ?, ?, ?)
				""", KEY, PAYLOAD_HASH, status, responseBody);
	}

	/**
	 * Built from the {@link DataSource} rather than injected, because a {@code JdbcTemplate}
	 * the slice happened to expose would be one more thing this test's configuration
	 * depends on — and every variation in configuration is another cached application
	 * context (design decision 25).
	 */
	private JdbcTemplate jdbc() {
		if (jdbc == null) {
			jdbc = new JdbcTemplate(dataSource);
		}
		return jdbc;
	}

	/** Stands in for ticket 17's money-moving transaction: the one these writes join or outlive. */
	private TransactionTemplate operation() {
		if (operation == null) {
			operation = new TransactionTemplate(transactionManager);
		}
		return operation;
	}
}
