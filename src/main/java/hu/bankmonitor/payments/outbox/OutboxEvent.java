package hu.bankmonitor.payments.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * A record that something happened to a Transfer, written as part of the same change that
 * caused it and delivered to other services afterwards.
 *
 * <p><b>Its existence is guaranteed by the change it describes</b>, and that is the whole of
 * why this table is here. A broker publish cannot join a database transaction, so "commit,
 * then send" is two writes with nothing spanning them, and a crash between them loses the
 * news of a Transfer that really did settle. A row written by the same transaction cannot
 * disagree with what that transaction did — it commits with it, or it never existed.
 * Design decision 12 has the alternatives, Kafka among them.
 *
 * <p><b>{@code sentAt} is null until the poller has published the event.</b> That is the
 * shape {@code CheckLedgerEntry} uses for an unanswered Check, and here the argument is
 * narrower still: there are exactly two states and one of them is the absence of the other,
 * so a status enum would have a constant whose only content is "not the other one".
 *
 * <p><b>The Transfer is an identifier, and there is no foreign key.</b> {@code check_ledger}
 * has one because {@code transfers.checks} sits inside the slice it points at; this slice is
 * one {@code transfers} depends on, and a constraint from here would be the outbox pointing
 * back at it. What the identifier is for is routing and correlation, not a relationship this
 * package can navigate.
 *
 * <p><b>The event type is a string this package does not interpret.</b> The outbox is
 * transport: it knows an event has a type, a subject and a payload, and nothing about what
 * any of them mean. An enum here — and the check constraint that would come with it — would
 * make ticket 28's two event types an edit to this slice rather than a use of it, and would
 * put the vocabulary of {@code transfers} inside the package {@code transfers} depends on.
 * The cost is real: a misspelt type is a row nobody consumes, so the slice that emits one
 * names it from a constant of its own.
 *
 * <p>There is deliberately no mutator, on {@code CheckLedgerEntry}'s precedent. Publishing is
 * at-least-once and a run may meet a row another run has already published, so marking one
 * sent is the guarded update {@link OutboxEventRepository#markSent} rather than a setter that
 * offers a second way to do it without the guard.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long transferId;

	@Column(length = 40)
	private String eventType;

	@Column(length = 4000)
	private String payload;

	private Instant occurredAt;

	private Instant sentAt;

	protected OutboxEvent() {
		// required by JPA
	}

	/**
	 * An event nobody has published yet, which is the only state one comes into being in.
	 *
	 * <p>The constructor is package-private rather than public: {@link OutboxEventRecorder} is
	 * the one thing that may write a row, because it is the one thing that refuses to run
	 * outside a transaction. A caller that could build an event could hand it to a repository
	 * of its own, and the guarantee this type exists for would be gone.
	 *
	 * <p>The instant is a parameter rather than an {@code Instant.now()}, on {@code Transfer}'s
	 * precedent: this application reads one {@link java.time.Clock}, and a clock a test can hold
	 * still is the difference between asserting a timestamp and hoping about one.
	 */
	OutboxEvent(Long transferId, String eventType, String payload, Instant occurredAt) {
		this.transferId = Objects.requireNonNull(transferId, "an event happens to a transfer");
		this.eventType = Objects.requireNonNull(eventType, "an event is of some type");
		this.payload = Objects.requireNonNull(payload, "an event carries what a consumer needs");
		this.occurredAt = Objects.requireNonNull(occurredAt, "an event happens at a time");
	}

	public Long getId() {
		return id;
	}

	/** The Transfer this happened to, which is how a consumer knows what to ask us about. */
	public Long getTransferId() {
		return transferId;
	}

	/** What happened, in the vocabulary of the slice that recorded it. */
	public String getEventType() {
		return eventType;
	}

	/**
	 * What a consumer needs in order to act, as JSON.
	 *
	 * <p>Outbound events carry the payload while the browser-facing stream carries almost
	 * nothing. The asymmetry is deliberate and design decision 17 states it: the browser can
	 * call our API, and a Check service calling back for the amount is exactly the coupling
	 * design decision 11 rejected polling to avoid.
	 */
	public String getPayload() {
		return payload;
	}

	/** When the change this describes happened, which is also when this row was written. */
	public Instant getOccurredAt() {
		return occurredAt;
	}
}
