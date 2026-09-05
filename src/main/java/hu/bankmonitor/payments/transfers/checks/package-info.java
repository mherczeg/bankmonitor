/**
 * The check ledger: which checks a transfer requires, and how each has been answered.
 *
 * <p>A sub-package of {@code transfers} rather than a sibling, because a check ledger
 * has no meaning apart from the transfer it belongs to. Verdicts arrive on an
 * {@code /internal} endpoint — a human approver and an automated service are the same
 * kind of caller here.
 */
package hu.bankmonitor.payments.transfers.checks;
