package hu.bankmonitor.payments.transfers;

/**
 * Where a {@link Transfer} is in its lifecycle: {@link #PENDING} while its Checks are
 * outstanding, then one of the three terminal states.
 *
 * <p>The set is closed and the progression is one-way — a Transfer leaves {@code PENDING}
 * exactly once and never returns, which is what lets every advance be a conditional
 * update guarded on the status it is leaving. ADR-0001 has why the lifecycle exists at
 * all, and design decisions 8–15 are its mechanics.
 *
 * <p>Only {@link #SETTLED} moves money. All three terminal states release the source
 * Account's Reserved Amount.
 */
public enum TransferStatus {

	/** Requested, funds reserved, waiting on its Checks. The only non-terminal state. */
	PENDING,

	/** Every Check approved: the money moved. */
	SETTLED,

	/** A Check said no. The reservation is released and no money ever moved. */
	REJECTED,

	/** The Checks were not all answered in time. Releases the reservation, moves nothing. */
	EXPIRED
}
