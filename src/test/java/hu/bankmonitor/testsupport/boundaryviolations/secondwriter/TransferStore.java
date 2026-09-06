package hu.bankmonitor.testsupport.boundaryviolations.secondwriter;

/** Stands in for {@code TransferRepository}, which this package cannot name. */
public interface TransferStore {

	Object save(Object transfer);
}
