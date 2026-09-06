package hu.bankmonitor.payments.idempotency;

import hu.bankmonitor.testsupport.BootedApplicationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The four answers a repeat of an Idempotency Key can get, and the one thing all four have
 * in common: the operation behind the key does not run a second time.
 *
 * <p>Every case is arranged by writing the claim it starts from straight into the table,
 * rather than by driving {@link IdempotencyClaims} to produce it. The states this method
 * branches on are the states a *previous* request left behind, and placing them says which
 * one is under test in the one line that sets it up — where reaching them through the claim
 * mechanics would make each case depend on the mechanics of the case before it.
 *
 * <p>Not transactional, for {@code TheTableDecidesWhoHoldsAKeyTest}'s reason one layer up:
 * the claim commits alone and the operation commits with the flip that closes it, and a
 * test that never commits cannot tell either apart from a test that does. Rows therefore
 * outlive the method that wrote them, so the table is emptied by hand.
 */
class WhatARepeatOfAKeyGetsBackTest extends BootedApplicationTest {

	private static final String KEY = "0d1f6c1e-6b0a-4a5f-9f1a-2c3d4e5f6a7b";

	private static final String PAYLOAD_HASH = "1".repeat(64);

	private static final String A_DIFFERENT_PAYLOAD_HASH = "2".repeat(64);

	private static final Answer ANSWER = new Answer(31L, "PENDING");

	private static final String STORED_ANSWER = "{\"id\":31,\"status\":\"PENDING\"}";

	private static final Answer A_DIFFERENT_ANSWER = new Answer(32L, "PENDING");

	/** Stands in for the response an endpoint hands back and a repeat of the key replays. */
	record Answer(long id, String status) {
	}

	@Autowired
	private IdempotentExecution requests;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private DataSource dataSource;

	private final AtomicInteger runs = new AtomicInteger();

	/** Phase two, counted separately: a duplicate must not pay for it either. */
	private final AtomicInteger resolutions = new AtomicInteger();

	@BeforeEach
	@AfterEach
	void dropEveryClaim() {
		jdbc.update("DELETE FROM idempotency_records");
	}

	@Test
	@DisplayName("a key nobody has claimed runs the operation and stores what it answered")
	void aFirstRequestRunsTheOperationAndStoresWhatItAnswered() {
		assertThat(executeOnce(answering(ANSWER))).isEqualTo(ANSWER);

		assertThat(runs).hasValue(1);
		assertThat(storedStatus()).isEqualTo("SUCCEEDED");
		assertThat(storedResponseBody()).isEqualTo(STORED_ANSWER);
	}

	/**
	 * The guarantee itself: the second caller is handed the first caller's answer, and the
	 * operation — which is where the money would move — is never reached. Both halves are
	 * asserted, because an implementation that ran the operation again and happened to
	 * return an equal answer would pass on the first half alone.
	 */
	@Test
	@DisplayName("a repeat of a succeeded key replays the stored answer without running anything")
	void aRepeatOfASucceededKeyReplaysTheStoredAnswer() {
		claimedKey("SUCCEEDED", PAYLOAD_HASH, STORED_ANSWER);

		assertThat(executeOnce(answering(A_DIFFERENT_ANSWER))).isEqualTo(ANSWER);

		assertThat(runs).hasValue(0);
	}

	@Test
	@DisplayName("a repeat of a key whose work is unfinished is refused as in progress")
	void aRepeatOfAnInProgressKeyIsRefusedAsInProgress() {
		claimedKey("IN_PROGRESS", PAYLOAD_HASH, null);

		assertThatThrownBy(() -> executeOnce(answering(ANSWER)))
				.isInstanceOf(RequestInProgressException.class);

		assertThat(runs).hasValue(0);
		assertThat(storedStatus()).isEqualTo("IN_PROGRESS");
	}

	/**
	 * The mismatch is read before the status, which is what makes this a single row in the
	 * table of outcomes rather than three. Whatever the first request got to, a second
	 * payload under its key is a client mistake — and answering the {@code SUCCEEDED} case
	 * with a replay would hand a client the response to a request it did not make.
	 */
	@ParameterizedTest(name = "over a claim that is {0}")
	@ValueSource(strings = {"IN_PROGRESS", "SUCCEEDED", "FAILED"})
	@DisplayName("a key carrying a different payload is refused as reused")
	void aKeyReusedForADifferentPayloadIsRefusedWhateverItsClaimHasReached(String status) {
		claimedKey(status, PAYLOAD_HASH, "SUCCEEDED".equals(status) ? STORED_ANSWER : null);

		assertThatThrownBy(() -> requests.executeOnce(KEY, A_DIFFERENT_PAYLOAD_HASH,
				Answer.class, nothingToResolve(), resolved -> answering(ANSWER).get()))
				.isInstanceOf(IdempotencyKeyReusedException.class);

		assertThat(runs).hasValue(0);
		assertThat(resolutions).hasValue(0);
		assertThat(storedStatus()).isEqualTo(status);
	}

	/**
	 * The row that makes the whole design work. A failed attempt leaves a key its own client
	 * can take back, so every reservation-time failure is recoverable by sending the same
	 * request again — which is what lets the client's retry policy be one line.
	 */
	@Test
	@DisplayName("a retry of a failed key takes the claim back and executes")
	void aRetryOfAFailedKeyExecutes() {
		claimedKey("FAILED", PAYLOAD_HASH, null);

		assertThat(executeOnce(answering(ANSWER))).isEqualTo(ANSWER);

		assertThat(runs).hasValue(1);
		assertThat(storedStatus()).isEqualTo("SUCCEEDED");
		assertThat(storedResponseBody()).isEqualTo(STORED_ANSWER);
	}

	/**
	 * A failure is reported to its caller <em>and</em> released, which are two different
	 * postconditions and only one of them is visible in the exception. Without the second,
	 * a single refused request would burn its key permanently — the client is told to wait
	 * for work that has already stopped.
	 */
	@Test
	@DisplayName("an operation that throws releases the key, and the failure reaches the caller")
	void anOperationThatThrowsReleasesTheKeyForARetry() {
		assertThatThrownBy(() -> executeOnce(failing()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("the reservation could not be made");

		assertThat(storedStatus()).isEqualTo("FAILED");
		assertThat(storedResponseBody()).isNull();

		assertThat(executeOnce(answering(ANSWER))).isEqualTo(ANSWER);
		assertThat(storedStatus()).isEqualTo("SUCCEEDED");
	}

	/**
	 * Design decision 4's phase two, and the placement that is its entire point: it runs
	 * with the key already claimed and with no transaction open, where the operation runs
	 * inside one.
	 *
	 * <p>Both halves matter and only together. That the resolution ran <em>after</em> the
	 * claim is what stops a duplicate paying for it; that it ran <em>outside</em> the
	 * transaction is what keeps a slow Exchange Rate provider from being something the
	 * Account row locks wait on. Moving the call one line down, inside the transaction
	 * template, would leave every other test in this class green.
	 */
	@Test
	@DisplayName("phase two runs under the claim and outside the transaction the operation runs in")
	void resolvesWithTheKeyClaimedAndNoTransactionOpen() {
		AtomicBoolean transactionWhileResolving = new AtomicBoolean(true);
		AtomicBoolean transactionWhileOperating = new AtomicBoolean(false);
		AtomicReference<String> claimWhileResolving = new AtomicReference<>();

		Answer answered = requests.executeOnce(KEY, PAYLOAD_HASH, Answer.class,
				() -> {
					transactionWhileResolving.set(TransactionSynchronizationManager.isActualTransactionActive());
					claimWhileResolving.set(storedStatus());
					return "the resolved value";
				},
				resolved -> {
					transactionWhileOperating.set(TransactionSynchronizationManager.isActualTransactionActive());
					assertThat(resolved).as("what phase two produced reaches phase three")
							.isEqualTo("the resolved value");
					return ANSWER;
				});

		assertThat(answered).isEqualTo(ANSWER);
		assertThat(transactionWhileResolving).as("nothing slow may run inside the transaction").isFalse();
		assertThat(transactionWhileOperating).as("the operation and the claim commit together").isTrue();
		assertThat(claimWhileResolving).as("the claim is committed before phase two runs")
				.hasValue("IN_PROGRESS");
	}

	/**
	 * The failure ticket 26 exists to handle: the Exchange Rate provider gave up, so nothing
	 * was priced and nothing may be reserved. Failing to the caller is not giving up on the
	 * key — the claim is released, so the same key resubmitted executes rather than replaying
	 * a failure.
	 */
	@Test
	@DisplayName("a phase two that throws releases the key without ever reaching the operation")
	void aResolutionThatThrowsReleasesTheKeyAndRunsNothing() {
		assertThatThrownBy(() -> executeOnce(
				() -> {
					resolutions.incrementAndGet();
					throw new IllegalStateException("the provider did not answer");
				},
				answering(ANSWER)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("the provider did not answer");

		assertThat(resolutions).hasValue(1);
		assertThat(runs).as("nothing may be reserved at a rate nobody quoted").hasValue(0);
		assertThat(storedStatus()).isEqualTo("FAILED");

		assertThat(executeOnce(answering(ANSWER))).isEqualTo(ANSWER);
		assertThat(storedStatus()).isEqualTo("SUCCEEDED");
	}

	/**
	 * The claim is committed before the operation runs, which is the property the whole
	 * mechanism rests on and the one a sequential test normally cannot see. Here the repeat
	 * arrives from inside the operation itself, so it is demonstrably reading a claim whose
	 * request has not finished.
	 *
	 * <p>What it leaves behind is the second half: the refusal travelled out through the
	 * operation, so the outer request failed too, and its key is free for the retry that
	 * both callers now owe.
	 */
	@Test
	@DisplayName("a repeat arriving while the operation is still running sees the claim")
	void aRepeatArrivingDuringTheOperationSeesTheClaim() {
		assertThatThrownBy(() -> executeOnce(() -> {
			runs.incrementAndGet();
			return requests.executeOnce(KEY, PAYLOAD_HASH, Answer.class,
					nothingToResolve(), resolved -> answering(ANSWER).get());
		})).isInstanceOf(RequestInProgressException.class);

		assertThat(runs).hasValue(1);
		assertThat(storedStatus()).isEqualTo("FAILED");
	}

	/**
	 * Releasing the claim can fail on its own, and it always fails while another exception
	 * is already on its way up. The one the caller asked about wins; the bookkeeping failure
	 * travels attached to it, because dropping it would leave a key stranded with nothing
	 * anywhere saying so.
	 *
	 * <p>The claim is deleted on a second connection so that the deletion is real by the
	 * time the operation throws — the operation's own transaction is about to roll back, and
	 * anything it wrote would roll back with it.
	 */
	@Test
	@DisplayName("a failure that cannot be recorded travels attached to the failure it reports")
	void aFailureThatCannotBeRecordedIsAttachedToTheOriginal() {
		assertThatThrownBy(() -> executeOnce(() -> {
			deleteTheClaimOnAnotherConnection();
			throw new IllegalStateException("the reservation could not be made");
		}))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("the reservation could not be made")
				.satisfies(failure -> assertThat(failure.getSuppressed())
						.singleElement()
						.isInstanceOf(EmptyResultDataAccessException.class));
	}

	private @Nullable Answer executeOnce(Supplier<Answer> operation) {
		return executeOnce(nothingToResolve(), operation);
	}

	private @Nullable Answer executeOnce(Supplier<String> resolution, Supplier<Answer> operation) {
		return requests.executeOnce(KEY, PAYLOAD_HASH, Answer.class, resolution, resolved -> operation.get());
	}

	/**
	 * Phase two for the tests that are not about it. It still counts its runs, so that every
	 * "the operation never ran" assertion above is also a claim that no Exchange Rate would
	 * have been fetched for that repeat.
	 */
	private Supplier<String> nothingToResolve() {
		return () -> {
			resolutions.incrementAndGet();
			return "nothing to resolve";
		};
	}

	private Supplier<Answer> answering(Answer answer) {
		return () -> {
			runs.incrementAndGet();
			return answer;
		};
	}

	private Supplier<Answer> failing() {
		return () -> {
			runs.incrementAndGet();
			throw new IllegalStateException("the reservation could not be made");
		};
	}

	private void claimedKey(String status, String payloadHash, @Nullable String responseBody) {
		jdbc.update("""
				INSERT INTO idempotency_records (idempotency_key, payload_hash, status, response_body)
				VALUES (?, ?, ?, ?)
				""", KEY, payloadHash, status, responseBody);
	}

	private void deleteTheClaimOnAnotherConnection() {
		try (Connection other = dataSource.getConnection();
				PreparedStatement statement = other
						.prepareStatement("DELETE FROM idempotency_records WHERE idempotency_key = ?")) {
			statement.setString(1, KEY);
			statement.executeUpdate();
		}
		catch (SQLException unreachable) {
			throw new IllegalStateException(unreachable);
		}
	}

	private @Nullable String storedStatus() {
		return readColumn("status");
	}

	private @Nullable String storedResponseBody() {
		return readColumn("response_body");
	}

	private @Nullable String readColumn(String column) {
		return jdbc.queryForObject(
				"SELECT " + column + " FROM idempotency_records WHERE idempotency_key = ?",
				String.class, KEY);
	}
}
