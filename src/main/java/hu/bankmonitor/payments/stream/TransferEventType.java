package hu.bankmonitor.payments.stream;

/**
 * The three hints this stream carries, one per way a Transfer can finish.
 *
 * <p>A Transfer leaves {@code PENDING} exactly once and never moves again, so this is the
 * complete set. There is deliberately no hint for a Transfer being <em>requested</em>: the
 * only browser that could care is the one that submitted it, and it already knows.
 *
 * <p><b>These names are a contract with a frontend that was built first.</b> Ticket 36 wrote
 * {@code frontend/src/api/events.ts} against them before this endpoint existed, and that
 * module ignores a type it does not recognise — so a rename here is not a compile error and
 * not a runtime failure, it is a live-update feature that silently stops working. Screaming
 * case because that is how Jackson serialises an enum constant by default, which is what the
 * frontend was written to read.
 */
public enum TransferEventType {

	/** Every Check approved and the money moved. */
	TRANSFER_SETTLED,

	/** A Check said no. The reservation was released and no money moved. */
	TRANSFER_REJECTED,

	/** The Checks were not all answered in time. The reservation was released. */
	TRANSFER_EXPIRED
}
