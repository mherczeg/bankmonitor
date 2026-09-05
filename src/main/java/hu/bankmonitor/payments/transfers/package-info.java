/**
 * The transfer lifecycle: requesting a transfer, reserving funds for it, and settling,
 * rejecting or expiring it once its checks are answered.
 *
 * <p>This is the only package that depends on the other slices — {@code accounts},
 * {@code fx}, {@code idempotency} and {@code outbox} — and none of them depends back.
 * The transactional boundaries of the lifecycle live here, so the
 * {@code @Transactional}-visibility trap documented on {@link hu.bankmonitor.payments}
 * applies to this package first.
 */
package hu.bankmonitor.payments.transfers;
