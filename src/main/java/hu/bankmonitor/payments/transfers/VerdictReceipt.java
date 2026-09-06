package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.transfers.checks.Check;
import hu.bankmonitor.payments.transfers.checks.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a Check service is told once its Verdict has been recorded: the report it made, and
 * where the Transfer got to as a result.
 *
 * <p><b>{@code transferStatus} is the whole point of answering with a body at all.</b> A
 * Check service that reports one of two Checks learns its Verdict is not yet the last word;
 * one that reports the last learns that money moved. It is the same member
 * {@link TransferNotPendingException} puts on the wire when the report was too late, so
 * "where is this Transfer now" is answered in one vocabulary whether the report landed or
 * was refused.
 *
 * <p>The report is echoed back rather than assumed, because this endpoint is called by
 * machines with retries and logs: a receipt that named only a status would not say which of
 * several in-flight reports it was the receipt for.
 *
 * <p>Every member is listed as required for {@code TransferResponse}'s reason — springdoc
 * derives {@code required} from constraint annotations, and a response is never validated.
 */
@Schema(requiredProperties = {"transferId", "check", "verdict", "transferStatus"})
record VerdictReceipt(long transferId, Check check, Verdict verdict, TransferStatus transferStatus) {
}
