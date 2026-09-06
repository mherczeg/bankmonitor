package hu.bankmonitor.payments.transfers.checks;

/**
 * A condition a Transfer must satisfy before it can settle, answered by a party outside
 * this context.
 *
 * <p>A human approver and an automated service are the same kind of thing here: each
 * answers one of these constants for one Transfer, through the same operation. That
 * indistinguishability is what the asynchronous lifecycle was chosen for — a new condition
 * is a constant here and a line in {@link CheckPolicy}, not a new mechanism.
 *
 * <p>The constants are stored by name, so they are part of the schema: {@code
 * V4__check_ledger.sql} lists them in a check constraint, and renaming one is a migration
 * rather than a refactor.
 */
enum Check {

	/** Automated fraud screening, answered by a service the outbox pushes the request to. */
	FRAUD,

	/** A person signing off in a back-office tool, reporting through the same endpoint. */
	MANUAL_APPROVAL
}
