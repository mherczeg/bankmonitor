/**
 * The transactional outbox: events written as part of the same change that caused them,
 * and delivered to other services afterwards.
 *
 * <p>Exports one port: publishing an event. That it is a database row rather than a
 * broker call is exactly the detail the port hides, and the reason an event's existence
 * is guaranteed by the change it describes. The poller that drains the table is
 * package-private.
 */
package hu.bankmonitor.payments.outbox;
