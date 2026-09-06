package hu.bankmonitor.payments.fx;

import hu.bankmonitor.payments.common.Currency;

/**
 * Asks a third party what one currency is worth in another.
 *
 * <p>One of this application's three ports, and the only one whose other side is somebody
 * else's server. Everything that makes talking to that server survivable — the timeouts,
 * the bounded retry, the mapping of its vocabulary onto ours — lives in the implementation
 * behind this interface, so a caller writes the same line whether the rate came from a
 * stand-in on this JVM or from a paid provider across the internet.
 *
 * <p>The interface says nothing about HTTP on purpose. A second implementation reading
 * rates from a table, or from a cache in front of the network, would satisfy it without
 * any caller changing.
 */
public interface ExchangeRateProvider {

	/**
	 * Quotes {@code quote} per one unit of {@code base}.
	 *
	 * <p>Callers should not ask for a pair whose currencies are equal: the answer is one,
	 * every implementation of this port knows it, and a network round trip to be told so is
	 * a round trip that can fail.
	 *
	 * @throws ExchangeRateUnavailableException if the provider did not answer within the
	 *                                          retry budget, which is a transient failure
	 *                                          the caller may hand back to its own client
	 */
	ExchangeRate exchangeRateFor(Currency base, Currency quote);
}
