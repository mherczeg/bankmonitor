/**
 * The transactional outbox: events written as part of the same change that caused them,
 * and delivered to other services afterwards.
 *
 * <p>Exports one port: publishing an event. That it is a database row rather than a
 * broker call is exactly the detail the port hides, and the reason an event's existence
 * is guaranteed by the change it describes. The poller that drains the table is
 * package-private.
 *
 * <p>It exports a second public type that is not a port. {@link
 * hu.bankmonitor.payments.outbox.EventPublisher} faces outwards and is the seam a broker
 * would arrive at; {@link hu.bankmonitor.payments.outbox.OutboxEventRecorder} faces inwards
 * and is what a change in {@code transfers} calls to say what it did. Only the first has an
 * alternative implementation to be a seam for, which is why the count of ports is still
 * three.
 *
 * <p>The two halves never meet in one transaction, and that is the design rather than an
 * accident of layout. Recording refuses to run outside the caller's transaction, so an event
 * cannot commit without its change. Publishing runs in none, so a transport doing I/O cannot
 * hold a transaction open across it. What joins them is the row, and what the gap costs is a
 * possible duplicate — delivery is at-least-once, and consumers deduplicate.
 */
package hu.bankmonitor.payments.outbox;
