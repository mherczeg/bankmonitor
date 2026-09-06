/**
 * The check ledger: which checks a transfer requires, and how each has been answered.
 *
 * <p>A sub-package of {@code transfers} rather than a sibling, because a check ledger
 * has no meaning apart from the transfer it belongs to. Verdicts arrive on an
 * {@code /internal} endpoint — a human approver and an automated service are the same
 * kind of caller here.
 *
 * <p><b>One type and two methods leave this package:</b> {@link
 * hu.bankmonitor.payments.transfers.checks.CheckLedger}, with {@code openFor}, which
 * {@code FundsReservation} calls inside the transaction that writes the transfer, and
 * {@code record}, which {@code VerdictRecording} calls inside the transaction that advances
 * it. The rows, the repository and the policy that chooses them stay package-private, so
 * there is no way to reach in and write a verdict, or to read the rows and act on them, that
 * the compiler would allow.
 *
 * <p>What ticket 20 added to that surface is the <em>vocabulary</em> rather than a second
 * way in: {@code Check}, {@code Verdict} and {@code LedgerDecision} are public because
 * {@code record}'s signature is made of them — a caller that cannot name a check cannot
 * report one. {@code LedgerDecision.decide} stays package-private, because it is specified
 * over the rows and the rows do not leave. Ticket 22 is where the ledger itself first goes
 * on the wire, and it needs no more than this.
 */
package hu.bankmonitor.payments.transfers.checks;
