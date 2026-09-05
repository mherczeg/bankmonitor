/**
 * Exchange rates for cross-currency transfers, and the conversion one is used for.
 *
 * <p>Exports a port — asking for the rate between two currencies — and
 * {@link hu.bankmonitor.payments.fx.CurrencyConversion}, the pure function that applies
 * one. The provider is unreliable by design, so the HTTP client behind that port — its
 * timeouts, its retries and its mapping of failures onto this application's errors — is
 * package-private and replaceable without any caller noticing.
 *
 * <p>The split is deliberate: the arithmetic is the part most likely to be wrong and the
 * part easiest to test, so it is kept clear of everything that makes the provider hard to
 * test. The conversion knows nothing about where a rate came from.
 */
package hu.bankmonitor.payments.fx;
