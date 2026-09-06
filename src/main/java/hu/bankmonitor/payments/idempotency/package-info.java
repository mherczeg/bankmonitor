/**
 * Replay protection for client-supplied idempotency keys, so that retrying a request
 * never moves money twice.
 *
 * <p><b>{@link hu.bankmonitor.payments.idempotency.IdempotentExecution} and its two
 * refusals are the whole of what this package exports</b>, which is the seam design
 * decision 3 asks for: a caller hands over a key, what its request said, and the work — and
 * gets back either the work's answer or the answer the first request produced. Everything
 * behind it is package-private, so the storage and the transaction boundaries that make a
 * claim mutually exclusive cannot be reached from another slice at all, and the named
 * replacements §3 holds a place for — a pessimistic lock instead of a constraint violation,
 * a Redis-backed store, folding into the outbox — are changes to one class in here.
 *
 * <p>The two refusals are exported because a caller has to tell them apart: they share a
 * status code and give opposite advice about retrying, and the endpoint is where a status
 * code is decided. What is deliberately not exported is anything that would let a caller
 * ask <em>how far</em> a claim has got — that question is answered here, once, and only as
 * one of the four outcomes above.
 */
package hu.bankmonitor.payments.idempotency;
