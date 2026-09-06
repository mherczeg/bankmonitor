package hu.bankmonitor.payments.outbox;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Persistence for {@link OutboxEvent}, package-private so that reaching it from another
 * slice does not compile (design decision 30).
 *
 * <p>The bare {@link Repository} marker rather than {@code JpaRepository}, on {@code
 * Account}'s precedent: it declares nothing, so every method here is one with a call site
 * today.
 */
interface OutboxEventRepository extends Repository<OutboxEvent, Long> {

	/** Writes one event, which happens only inside the transaction of the change it describes. */
	OutboxEvent save(OutboxEvent event);

	/**
	 * The next events nobody has published, oldest first — the poller's whole query.
	 *
	 * <p>It is bounded because the table only grows — archival is one of the four deferrals
	 * {@code docs/deferred.md} records against this slice — so an unbounded read would one
	 * day load the history of the system into a scheduled method. What the limit costs is
	 * nothing: the rows it leaves behind are still unsent, and the next run is a second away.
	 *
	 * <p>The ordering is by insertion and not by {@code occurredAt}: it decides which rows a
	 * bounded read takes, so it has to be total, and two events written in one transaction
	 * share an instant. It is <em>not</em> a delivery-order guarantee — publishing is
	 * at-least-once and a failure mid-batch reorders the retry. Ordering between two events
	 * on one Transfer is deferred, and consumers are expected to tolerate its absence.
	 */
	List<OutboxEvent> findBySentAtIsNullOrderByIdAsc(Limit limit);

	/**
	 * Marks one event published, and reports whether this call was the one that did it.
	 *
	 * <p><b>{@code sentAt is null} is the guard</b>, on {@code CheckLedgerRepository.answer}'s
	 * precedent. Nothing today can lose that race — one instance runs one poll at a time — but
	 * the row is the only place the fact lives, and a second instance reading the same unsent
	 * rows is a named break of the single-instance assumption rather than an impossibility.
	 * Under the guard the second writer changes nothing instead of overwriting a timestamp
	 * that already said when the event went out.
	 *
	 * <p>It carries its own transaction because the poller runs outside one. Publishing is
	 * I/O, and holding a transaction open across it — trivially a log line here, a broker
	 * round trip once {@code publish} is swapped — is the shape {@code AccountLocking} exists
	 * to keep out of the locked path. So each row is published first and marked second, each
	 * mark in a transaction of its own: that ordering is what makes delivery at-least-once
	 * rather than at-most-once, and a mark that never commits costs a duplicate consumers
	 * already have to tolerate.
	 */
	@Transactional
	@Modifying
	@Query("""
			UPDATE OutboxEvent event SET event.sentAt = :sentAt
			WHERE event.id = :id
			  AND event.sentAt IS NULL
			""")
	int markSent(Long id, Instant sentAt);
}
