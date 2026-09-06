package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.transfers.checks.Verdict;
import jakarta.validation.constraints.NotNull;

/**
 * What reporting a Verdict takes: the answer, and nothing else.
 *
 * <p>The Transfer and the Check are in the path rather than here, because together they
 * address the thing being reported on — the one outstanding row in one Transfer's Check
 * Ledger. Carrying either in the body as well would let a request name one Transfer in its
 * URL and another in its payload, and something would then have to decide which to believe;
 * {@code CreateTransferRequest} deletes the same class of bug by leaving the Currency out.
 *
 * <p>A record with one member rather than a bare {@link Verdict} on the wire, so that the
 * request has somewhere to grow: a reporting Check service with a reason, a correlation
 * identifier or the instant it decided adds a member here without changing the endpoint's
 * shape.
 */
record VerdictReport(@NotNull Verdict verdict) {
}
