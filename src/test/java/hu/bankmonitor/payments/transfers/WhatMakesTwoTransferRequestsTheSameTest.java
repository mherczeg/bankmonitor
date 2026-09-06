package hu.bankmonitor.payments.transfers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one comparison an Idempotency Key is checked against: whether the request arriving
 * under it says the same thing as the request that claimed it.
 *
 * <p>Two payloads are the same when all three of their components are, which is what makes
 * the hash a fair test of "did this client mean to retry" — and different in any one of them
 * is a different Transfer wearing a key that already stands for another.
 */
class WhatMakesTwoTransferRequestsTheSameTest {

	private static final CreateTransferRequest REQUEST = new CreateTransferRequest(5L, 9L, 100_50L);

	@Test
	@DisplayName("the same Transfer asked for twice hashes to the same value")
	void theSameRequestHashesTheSame() {
		assertThat(new CreateTransferRequest(5L, 9L, 100_50L).payloadHash())
				.isEqualTo(REQUEST.payloadHash());
	}

	/**
	 * The swapped-Accounts case is the one worth naming: {@code 5 → 9} and {@code 9 → 5} are
	 * opposite Transfers of the same amount, and an encoding that ran the components together
	 * without saying where each ends would give them one hash. So would {@code 5 → 9} of 100
	 * against {@code 5 → 91} of 0, which is why the separator is not optional.
	 */
	@Test
	@DisplayName("a Transfer between the same two Accounts in the other direction hashes differently")
	void aReversedTransferHashesDifferently() {
		assertThat(new CreateTransferRequest(9L, 5L, 100_50L).payloadHash())
				.isNotEqualTo(REQUEST.payloadHash());
	}

	@Test
	@DisplayName("a different source Account hashes differently")
	void aDifferentSourceAccountHashesDifferently() {
		assertThat(new CreateTransferRequest(6L, 9L, 100_50L).payloadHash())
				.isNotEqualTo(REQUEST.payloadHash());
	}

	@Test
	@DisplayName("a different destination Account hashes differently")
	void aDifferentDestinationAccountHashesDifferently() {
		assertThat(new CreateTransferRequest(5L, 8L, 100_50L).payloadHash())
				.isNotEqualTo(REQUEST.payloadHash());
	}

	@Test
	@DisplayName("a different amount hashes differently")
	void aDifferentAmountHashesDifferently() {
		assertThat(new CreateTransferRequest(5L, 9L, 100_51L).payloadHash())
				.isNotEqualTo(REQUEST.payloadHash());
	}

	/**
	 * The column the hash is stored in is {@code varchar(64)}, so a hash that is anything but
	 * 64 hex characters is a write that fails at the database rather than a claim that fails
	 * to compare. Asserted as a shape rather than against a literal digest: what matters is
	 * that the encoding fits the column, not which of two equally good encodings was picked.
	 */
	@Test
	@DisplayName("a hash is the 64 lowercase hex characters the column holds")
	void aHashFitsTheColumnItIsStoredIn() {
		assertThat(REQUEST.payloadHash()).hasSize(64).matches("[0-9a-f]{64}");
	}
}
