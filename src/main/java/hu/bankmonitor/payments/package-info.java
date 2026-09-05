/**
 * Package-by-feature: one package per slice of the domain, not one per layer.
 *
 * <p>Dependencies run one way — {@code transfers} depends on {@code accounts},
 * {@code fx}, {@code idempotency} and {@code outbox}, and nothing points back.
 * {@code mockfx} depends on nothing at all. Every package here documents its own
 * public surface; between them there are only three ports.
 *
 * <p>What makes this more than folder tidying is that Java's default access level is
 * package-private and the compiler enforces it. A {@code TransferRepository} declared
 * with no modifier is uncallable from outside {@code transfers} — crossing that boundary
 * does not compile, so it is not a convention anyone has to remember.
 * {@code ModuleBoundariesHoldTest} covers the two things the compiler cannot see: that the
 * slices are free of cycles, and that no controller reaches a repository directly.
 *
 * <p><strong>Trap.</strong> {@code @Transactional} on a non-public method is silently
 * ignored under proxy-based AOP — no error, no warning, no transaction. Spring 6 relaxed
 * this for CGLIB proxies, but the failure mode is silent either way, so the rule holds
 * regardless of proxy strategy: the <em>class</em> may be package-private, the
 * {@code @Transactional} <em>method</em> stays public.
 *
 * <p>See design decision 30 for the alternatives this was chosen over.
 */
package hu.bankmonitor.payments;
