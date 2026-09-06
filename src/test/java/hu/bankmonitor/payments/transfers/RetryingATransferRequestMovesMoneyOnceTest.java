package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.ProblemType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The requirement itself, over a real socket: the same Transfer requested twice under one
 * Idempotency Key moves money once.
 *
 * <p>{@code WhatARepeatOfAKeyGetsBackTest} makes the same four claims against the port with
 * a stand-in operation, and {@link TransferRequestContractTest} makes them against the wire
 * with the port stubbed. Each is green while the two halves are wired to nothing, and each
 * has to place the claim it starts from by hand. Here nothing is placed: every state a
 * repeat meets was left behind by an earlier request through the same endpoint, which is
 * the only arrangement that can be wrong in the way production would be.
 *
 * <p>It is also the only place the bundling of design decision 4 is visible. Phase three
 * commits the reservation, the Transfer and the flip to {@code SUCCEEDED} together, so the
 * assertion that says so is not about any one of them: it is that all three are there after
 * a request that worked, and that none of them is after a request that was refused.
 */
class RetryingATransferRequestMovesMoneyOnceTest extends TransferScenario {

	private static final long SOURCE = 1L;

	private static final long DESTINATION = 2L;

	private static final long BALANCE = 1_000_00L;

	private static final long AMOUNT = 100_50L;

	private static final String KEY = "0d1f6c1e-6b0a-4a5f-9f1a-2c3d4e5f6a7b";

	private static final String A_SECOND_KEY = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

	private static final String TRANSFER = """
			{"fromAccountId": 1, "toAccountId": 2, "amountMinorUnits": 10050}""";

	private static final String A_DIFFERENT_TRANSFER = """
			{"fromAccountId": 1, "toAccountId": 2, "amountMinorUnits": 20000}""";

	/**
	 * {@link TransferScenario} empties the three tables a Transfer touches and does not
	 * know about this one, which outlives every method here for the same reason those do:
	 * a claim that rolled back with its test has serialised nothing.
	 */
	@BeforeEach
	@AfterEach
	void emptyTheIdempotencyTable() {
		database.update("DELETE FROM idempotency_records");
	}

	@BeforeEach
	void openTheTwoAccounts() {
		openAccount(SOURCE, BALANCE, Currency.EUR);
		openAccount(DESTINATION, BALANCE, Currency.EUR);
	}

	/**
	 * The guarantee. The second request is answered from what the first one stored, so the
	 * two responses agree member for member and point at the same Transfer — and the tables
	 * say that Transfer is the only one, with the amount reserved once rather than twice.
	 *
	 * <p>Both halves are needed. A service that reserved the funds again and returned the
	 * second Transfer would satisfy neither, but one that reserved again and happened to
	 * answer with the first Transfer's shape would satisfy the response assertion alone.
	 */
	@Test
	@DisplayName("the same Transfer sent twice under one key creates one Transfer and reserves once")
	void aRepeatIsAnsweredFromTheFirstRequestAndReservesNothingMore() {
		TransferResponse first = requestTransfer(KEY, TRANSFER).expectStatus().isCreated()
				.expectBody(TransferResponse.class).returnResult().getResponseBody();

		TransferResponse repeat = requestTransfer(KEY, TRANSFER).expectStatus().isCreated()
				.expectHeader().valueEquals(HttpHeaders.LOCATION, "/api/transfers/" + first.id())
				.expectBody(TransferResponse.class).returnResult().getResponseBody();

		assertThat(repeat).isEqualTo(first);
		assertThat(transferRows()).singleElement()
				.extracting(row -> row.get("ID")).isEqualTo(first.id());
		assertThat(reservedAmountOf(SOURCE)).isEqualTo(AMOUNT);
		assertThat(availableBalanceOf(SOURCE)).isEqualTo(BALANCE - AMOUNT);
		assertThat(claimedStatus(KEY)).isEqualTo("SUCCEEDED");
	}

	/**
	 * Design decision 4's bundling, seen from the side where nothing happened: a refused
	 * request leaves no reservation, no Transfer and no Check Ledger, and its key is
	 * {@code FAILED} rather than stuck at {@code IN_PROGRESS}. Any of those four disagreeing
	 * with the others is the crash window the bundle exists to close.
	 */
	@Test
	@DisplayName("a refused request reserves nothing, writes nothing, and leaves its key retryable")
	void aRefusedRequestWritesNothingAndReleasesItsKey() {
		requestTransfer(KEY, tooLargeATransfer()).expectStatus().isEqualTo(422)
				.expectBody().jsonPath("$.type").isEqualTo(ProblemType.INSUFFICIENT_FUNDS.urn());

		assertThat(reservedAmountOf(SOURCE)).isZero();
		assertThat(transferRows()).isEmpty();
		assertThat(checkLedgerRows()).isEmpty();
		assertThat(claimedStatus(KEY)).isEqualTo("FAILED");
	}

	/**
	 * What a released key is for. The operator funds the Account and sends the very same
	 * request again — same key, same payload — and it goes through, which is what lets a
	 * client's retry policy be "resend it" rather than "work out whether the first one took
	 * effect".
	 */
	@Test
	@DisplayName("resending a request that was refused, once its reason is gone, creates the Transfer")
	void aRetryOfARefusedRequestExecutes() {
		requestTransfer(KEY, tooLargeATransfer()).expectStatus().isEqualTo(422);

		payInTwiceTheBalance();

		TransferResponse retried = requestTransfer(KEY, tooLargeATransfer())
				.expectStatus().isCreated()
				.expectBody(TransferResponse.class).returnResult().getResponseBody();

		assertThat(retried.status()).isEqualTo(TransferStatus.PENDING);
		assertThat(transferRows()).singleElement()
				.extracting(row -> row.get("ID")).isEqualTo(retried.id());
		assertThat(claimedStatus(KEY)).isEqualTo("SUCCEEDED");
	}

	/**
	 * The other {@code 409}, end to end: a key that already stands for one Transfer will not
	 * carry another, whatever the first one got to. No {@code Retry-After}, because no amount
	 * of retrying will make the two payloads agree — and no second Transfer, which is the
	 * refusal doing its job rather than merely announcing itself.
	 */
	@Test
	@DisplayName("a second Transfer sent under a spent key is refused, and none is created")
	void aKeyReusedForADifferentTransferIsRefused() {
		requestTransfer(KEY, TRANSFER).expectStatus().isCreated();

		requestTransfer(KEY, A_DIFFERENT_TRANSFER).expectStatus().isEqualTo(409)
				.expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
				.expectBody().jsonPath("$.type").isEqualTo(ProblemType.IDEMPOTENCY_KEY_REUSED.urn());

		assertThat(transferRows()).hasSize(1);
		assertThat(reservedAmountOf(SOURCE)).isEqualTo(AMOUNT);
	}

	/**
	 * The key is what identifies a repeat, and nothing else is: the same payload under a new
	 * key is a second Transfer the operator meant to make, not a retry to be swallowed.
	 */
	@Test
	@DisplayName("the same Transfer under a new key is a second Transfer")
	void theSameTransferUnderANewKeyIsASecondTransfer() {
		requestTransfer(KEY, TRANSFER).expectStatus().isCreated();
		requestTransfer(A_SECOND_KEY, TRANSFER).expectStatus().isCreated();

		assertThat(transferRows()).hasSize(2);
		assertThat(reservedAmountOf(SOURCE)).isEqualTo(2 * AMOUNT);
	}

	private RestTestClient.ResponseSpec requestTransfer(String idempotencyKey, String body) {
		return client().post().uri("/api/transfers")
				.header("X-Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange();
	}

	/** More than the source Account holds, so the overdraft check refuses it under the lock. */
	private static String tooLargeATransfer() {
		return """
				{"fromAccountId": 1, "toAccountId": 2, "amountMinorUnits": %d}"""
				.formatted(BALANCE * 2);
	}

	private void payInTwiceTheBalance() {
		database.update("UPDATE accounts SET balance_minor_units = ? WHERE id = ?",
				BALANCE * 3, SOURCE);
	}

	private String claimedStatus(String idempotencyKey) {
		return database.queryForObject(
				"SELECT status FROM idempotency_records WHERE idempotency_key = ?",
				String.class, idempotencyKey);
	}
}
