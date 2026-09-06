package hu.bankmonitor.testsupport.boundaryviolations.secondwriter;

/**
 * The mistake {@code NothingButTheReservationCreatesATransferTest} exists to catch: a
 * second class that can write a Transfer, and so a second way to produce one with no Check
 * Ledger behind it.
 *
 * <p>Outside the packages the rule scans, so it breaks the rule on purpose without breaking
 * the build.
 */
public class SecondTransferWriter {

	private final TransferStore transfers;

	public SecondTransferWriter(TransferStore transfers) {
		this.transfers = transfers;
	}

	public Object writeOneWithNoLedger(Object transfer) {
		return transfers.save(transfer);
	}
}
