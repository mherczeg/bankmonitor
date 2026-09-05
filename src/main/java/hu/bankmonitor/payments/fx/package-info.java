/**
 * Exchange rates for cross-currency transfers, obtained from a provider outside this
 * context.
 *
 * <p>Exports one port: asking for the rate between two currencies. The provider is
 * unreliable by design, so the HTTP client behind that port — its timeouts, its retries
 * and its mapping of failures onto this application's errors — is package-private and
 * replaceable without any caller noticing.
 */
package hu.bankmonitor.payments.fx;
