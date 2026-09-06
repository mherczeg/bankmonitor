/**
 * Replay protection for client-supplied idempotency keys, so that retrying a request
 * never moves money twice.
 *
 * <p>The record of a key, and the mechanics of claiming one — and nothing else. This
 * package exports no type: every class in it is package-private, so the storage and the
 * transaction boundaries that make a claim mutually exclusive cannot be reached from
 * another slice at all.
 *
 * <p>The port design decision 3 describes — the operation that runs a body once per key
 * and returns the first result to every later caller — is not here yet. It arrives with
 * the first caller that has a use for it, which is the duplicate resolution of ticket 17.
 */
package hu.bankmonitor.payments.idempotency;
