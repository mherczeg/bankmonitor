package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.common.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

/**
 * A request to move money from one Account to another, and where that request has got to.
 *
 * <p>A Transfer is a lifecycle rather than an outcome: it is created {@link
 * TransferStatus#PENDING} with the amount reserved on the source Account, and reaches
 * {@code SETTLED}, {@code REJECTED} or {@code EXPIRED} once its Checks are answered. Only
 * settlement moves money.
 *
 * <p><b>Two amounts, because the two Accounts may be denominated differently.</b> The
 * debited amount is what leaves the source Account, in the source Account's currency; the
 * credited amount is what arrives, in the destination Account's. A same-currency Transfer
 * carries the same figure twice, which is the honest reading — the conversion happened, at
 * a rate of one.
 *
 * <p><b>The Accounts are referenced by ID, not by association.</b> An Account is only ever
 * read or written under the pessimistic lock design decision 6 requires, and an
 * association would offer a second way to reach one that quietly skips it.
 *
 * <p>There is deliberately no method here that advances the status. Each transition is
 * owned by the ticket that has a caller for it — reserving (13), the Verdict that settles
 * or rejects (20), and expiry (23) — and each is a conditional update guarded on the
 * status it is leaving, so a transition written now would be a setter for three callers
 * that do not exist.
 *
 * <p>One timestamp, for the same reason: the status is the single answer to whether a
 * Transfer is done, and the ticket that first needs to know <em>when</em> it finished can
 * add that column with a reader to shape it.
 */
@Entity
@Table(name = "transfers")
public class Transfer {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long sourceAccountId;

	private Long destinationAccountId;

	@Embedded
	@AttributeOverride(name = "minorUnits", column = @Column(name = "debited_amount_minor_units"))
	@AttributeOverride(name = "currency", column = @Column(name = "debited_amount_currency"))
	private Money debitedAmount;

	@Embedded
	@AttributeOverride(name = "minorUnits", column = @Column(name = "credited_amount_minor_units"))
	@AttributeOverride(name = "currency", column = @Column(name = "credited_amount_currency"))
	private Money creditedAmount;

	// If left unannotated, JPA stores the status by ordinal. See design decision 29.
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private TransferStatus status;

	private Instant createdAt;

	protected Transfer() {
		// required by JPA
	}

	/**
	 * Requests a Transfer, which is the only way one comes into being: {@code PENDING},
	 * with both amounts already known because the Exchange Rate is locked at request time
	 * (design decision 15).
	 *
	 * <p>The instant is a parameter rather than an {@code Instant.now()} because the
	 * deadline expiry tests against is measured from it, and a clock a test can drive is
	 * the difference between asserting expiry and sleeping through it.
	 */
	public Transfer(Long sourceAccountId, Long destinationAccountId,
			Money debitedAmount, Money creditedAmount, Instant requestedAt) {
		this.sourceAccountId = Objects.requireNonNull(sourceAccountId, "a transfer leaves an account");
		this.destinationAccountId = Objects.requireNonNull(destinationAccountId, "a transfer arrives somewhere");
		this.debitedAmount = Objects.requireNonNull(debitedAmount, "a transfer moves an amount");
		this.creditedAmount = Objects.requireNonNull(creditedAmount, "a transfer credits an amount");
		this.createdAt = Objects.requireNonNull(requestedAt, "a transfer happens at a time");
		this.status = TransferStatus.PENDING;
	}

	public Long getId() {
		return id;
	}

	/** The Account the money leaves, and the one whose Reserved Amount holds it meanwhile. */
	public Long getSourceAccountId() {
		return sourceAccountId;
	}

	/** The Account the money arrives at, credited only at settlement. */
	public Long getDestinationAccountId() {
		return destinationAccountId;
	}

	/** What leaves the source Account, in the source Account's currency. */
	public Money getDebitedAmount() {
		return debitedAmount;
	}

	/** What reaches the destination Account, in the destination Account's currency. */
	public Money getCreditedAmount() {
		return creditedAmount;
	}

	public TransferStatus getStatus() {
		return status;
	}

	/** When the Transfer was requested. The Transactions list is ordered on it. */
	public Instant getCreatedAt() {
		return createdAt;
	}
}
