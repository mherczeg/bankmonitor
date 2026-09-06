/**
 * The browser-facing event stream: one server-sent-events endpoint, and the registry of
 * browsers listening to it.
 *
 * <p>It exports no operation and no port — only the two value types a hint is made of,
 * {@link hu.bankmonitor.payments.stream.TransferEvent} and
 * {@link hu.bankmonitor.payments.stream.TransferEventType}. The endpoint, the registry and
 * the send are package-private, and nothing outside this package can reach a subscriber.
 *
 * <p>That is not tidiness. A change in {@code transfers} announces itself by publishing an
 * ordinary application event from inside its own transaction, and this slice listens for one
 * after that transaction commits — so the container is the seam, and nothing in
 * {@code transfers} names a type that sends. Why the send has to happen after the commit
 * rather than merely near it, and why a direct call would have been the wrong shape even if
 * the ordering had been safe, is on
 * {@link hu.bankmonitor.payments.stream.TransferEventStream}.
 *
 * <p>Named {@code stream} rather than {@code events} because {@code outbox} already owns the
 * word: an Outbox Event is a durable row addressed to another service and carries the payload
 * that service needs, while a message here is addressed to a browser, carries a Transfer ID
 * and is gone the moment it is written. Design decision 17 has why the two are deliberately
 * asymmetric.
 */
package hu.bankmonitor.payments.stream;
