package hu.bankmonitor.payments.transfers.checks;

import org.jspecify.annotations.Nullable;

/**
 * One Check a Transfer requires, and how it has been answered so far.
 *
 * <p><b>What leaves this package when the ledger is read.</b> {@link CheckLedgerEntry} stays
 * package-private for the reason its own Javadoc gives — a reader that could name a row could
 * be one edit away from writing one — so a caller reporting the ledger is handed the two
 * facts about a row and no way to reach the row itself. There is deliberately no identifier
 * here: a ledger row is addressed by the Transfer and the Check that name it, which is also
 * the pair {@code InternalVerdictController} answers one at.
 *
 * <p><b>An unanswered Check carries no Verdict</b>, which is {@link CheckLedgerEntry}'s claim
 * about the column carried out to the caller unchanged: "nobody has answered" is the absence
 * of an answer, not one of the answers. Collapsing it into a third constant here would put
 * back the three-valued shape that design decision 8 sketched and ticket 19 rejected, one
 * layer further out and with every reader of the wire now having to know which of the three
 * is not really a Verdict.
 */
public record CheckState(Check check, @Nullable Verdict verdict) {
}
