/**
 * Replay protection for client-supplied idempotency keys, so that retrying a request
 * never moves money twice.
 *
 * <p>Exports one port: the operation that runs a body once per key and returns the
 * first result to every later caller. Its storage and its concurrency handling are
 * package-private — callers state the intent, not the mechanism.
 */
package hu.bankmonitor.payments.idempotency;
