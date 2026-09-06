package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.stream.TransferEvent;
import hu.bankmonitor.payments.stream.TransferEventType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * How this slice says out loud that a Transfer has finished, for the browsers watching it.
 *
 * <p>It publishes an application event rather than calling the stream, and the indirection is
 * the point rather than ceremony: the container delivers it <em>after</em> the caller's
 * transaction commits, and it keeps a socket write off the locked path that every caller of
 * this class is on. Both properties are argued where they are relied on, on
 * {@code TransferEventStream}. What matters at this end is that <b>a caller announces from
 * inside its transaction</b> — announcing after committing would work by a fallback rather
 * than by the arrangement, and announcing from no transaction at all is not what any caller
 * here does.
 *
 * <p>Separate from {@code OutboxEventRecorder}, which the same callers reach for, and
 * deliberately the mirror image of it. An Outbox Event is written <em>in</em> the transaction
 * because its whole claim is that the record and the change commit together; a hint is sent
 * <em>after</em> the transaction because its whole claim is that what it points at is already
 * readable. One is a row addressed to a service and carries the payload; the other is two
 * fields addressed to a browser that can call our API.
 */
@Component
class LifecycleHints {

	private final ApplicationEventPublisher announcements;

	LifecycleHints(ApplicationEventPublisher announcements) {
		this.announcements = announcements;
	}

	/**
	 * Announces the status a Transfer has just reached, if it is one a browser is told about.
	 *
	 * <p>Callers pass whatever status they moved the Transfer to, including {@code PENDING},
	 * so that no call site has to decide whether this one is worth announcing. That keeps the
	 * decision in {@link #hintFor} where the compiler checks it, and it is why
	 * {@code VerdictRecording} announces once at the end rather than once per branch.
	 */
	void announce(long transferId, TransferStatus status) {
		hintFor(status).ifPresent(type -> announcements.publishEvent(new TransferEvent(type, transferId)));
	}

	/**
	 * The hint a status is worth, or nothing at all for the one status that is not news.
	 *
	 * <p>An exhaustive switch rather than a lookup or a naming convention, so that a fourth
	 * {@link TransferStatus} is a compile error here rather than a Transfer that finishes
	 * without any browser being told. That failure would be invisible from both ends: the
	 * backend is correct, the frontend is correct, and the page simply never updates.
	 *
	 * <p>{@code PENDING} maps to nothing because there is no message for a Transfer being
	 * requested — the only browser that could care is the one that submitted it, and it is
	 * already on its way to the Transfer's page.
	 */
	private static Optional<TransferEventType> hintFor(TransferStatus status) {
		return switch (status) {
			case PENDING -> Optional.empty();
			case SETTLED -> Optional.of(TransferEventType.TRANSFER_SETTLED);
			case REJECTED -> Optional.of(TransferEventType.TRANSFER_REJECTED);
			case EXPIRED -> Optional.of(TransferEventType.TRANSFER_EXPIRED);
		};
	}
}
