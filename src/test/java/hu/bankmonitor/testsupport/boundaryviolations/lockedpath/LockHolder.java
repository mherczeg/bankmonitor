package hu.bankmonitor.testsupport.boundaryviolations.lockedpath;

/**
 * Stands in for the operation that holds the row locks, and reaches an HTTP client the way
 * a real one would go wrong. Never wired into anything.
 *
 * <p>The call is one hop away rather than in this class, which is the case a rule reading
 * only direct dependencies would miss — and the reason the real rule is transitive.
 */
public class LockHolder {

	private final ExchangeRateLookup rates = new ExchangeRateLookup();

	public String lockAndQuote() {
		return rates.quote();
	}
}
