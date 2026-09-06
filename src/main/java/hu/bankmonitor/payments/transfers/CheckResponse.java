package hu.bankmonitor.payments.transfers;

import com.fasterxml.jackson.annotation.JsonInclude;
import hu.bankmonitor.payments.transfers.checks.Check;
import hu.bankmonitor.payments.transfers.checks.CheckState;
import hu.bankmonitor.payments.transfers.checks.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * One line of a Transfer's Check Ledger as the API reports it: a condition the Transfer has
 * to satisfy, and what the party answering it has said.
 *
 * <p><b>An outstanding Check has no {@code verdict} member at all.</b> The alternative is
 * sending {@code null}, and the two are different promises rather than two spellings of one:
 * an absent member is one a client is made to handle missing, where a null one is a value it
 * has to remember can be null. Absence is also the shape the rest of this build already
 * commits to — {@code CheckLedgerEntry} records an unanswered Check as an absent column
 * rather than as a third Verdict, and {@link hu.bankmonitor.payments.transfers.checks.CheckState}
 * carries that out unchanged. This is that same claim on the wire.
 *
 * <p>It is the opposite of the judgement {@code OpenApiConfiguration} records for a
 * validation error's {@code field}, and for the reason that gives: a class-level violation
 * belongs to the request rather than to any one input, so the member is there and null. A
 * Verdict nobody has given belongs to nothing, so there is nothing to send.
 *
 * <p>{@code check} is listed as required for {@code TransferResponse}'s reason — springdoc
 * derives {@code required} from constraint annotations and a response is never validated —
 * and {@code verdict} is left out of that list because it genuinely is optional, which is the
 * one case design decision 32 named when it rejected a customizer marking every property of
 * every schema required.
 */
@Schema(requiredProperties = "check")
record CheckResponse(Check check, @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Verdict verdict) {

	static CheckResponse of(CheckState state) {
		return new CheckResponse(state.check(), state.verdict());
	}
}
