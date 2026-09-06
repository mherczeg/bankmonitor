/**
 * The check ledger: which checks a transfer requires, and how each has been answered.
 *
 * <p>A sub-package of {@code transfers} rather than a sibling, because a check ledger
 * has no meaning apart from the transfer it belongs to. Verdicts arrive on an
 * {@code /internal} endpoint — a human approver and an automated service are the same
 * kind of caller here.
 *
 * <p><b>One type and one method leave this package:</b> {@link
 * hu.bankmonitor.payments.transfers.checks.CheckLedger} and its {@code openFor}, which
 * {@code FundsReservation} calls inside the transaction that writes the transfer. The
 * policy, the rows, the repository and the decision those rows drive are all
 * package-private, so opening a ledger is the only thing anything outside can do — there
 * is no way to reach in and write a verdict, or to read the rows and act on them, that the
 * compiler would allow.
 *
 * <p>That surface grows where a caller earns it: ticket 20 records verdicts and advances
 * the transfer from inside this package, and ticket 22 is the first thing that has to put
 * a ledger on the wire, which is where the two enums become public.
 */
package hu.bankmonitor.payments.transfers.checks;
