package hu.bankmonitor.payments.fx;

import hu.bankmonitor.payments.common.Currency;

import java.math.BigDecimal;

/**
 * The provider's answer as it arrives on the wire, in its vocabulary rather than ours.
 *
 * <p>Currencies are strings here because they are strings there. This application's
 * {@code Currency} is a closed enum of what it can price, and a third party's list is its
 * own; binding the response straight onto the enum would turn "the provider quoted
 * something we do not trade" into a deserialisation failure, which is the wrong shape of
 * error and the wrong place to notice it.
 *
 * <p>Deliberately a second declaration of the same three fields the stand-in provider
 * serves, rather than a shared type. The stand-in is a stand-in — a real provider replaces
 * it and this record changes with the wire, while {@code mockfx} stays a third party that
 * depends on nothing of ours.
 *
 * <p>The reasoning, and the alternatives rejected, are in
 * {@code docs/design-decisions/25-exchange-rate-client.md}.
 */
record ProviderQuote(String base, String quote, BigDecimal rate) {

	/**
	 * Whether this quote answers the question it was sent, comparing the provider's
	 * vocabulary against ours — which is the one place the two are allowed to meet.
	 */
	boolean matches(Currency base, Currency quote) {
		return base.name().equals(this.base) && quote.name().equals(this.quote);
	}
}
