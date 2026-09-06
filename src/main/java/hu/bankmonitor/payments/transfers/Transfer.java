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
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
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
 * <p><b>The Exchange Rate that relates them is stored here, with the moment it was
 * fetched</b>, and does not change again for the life of the Transfer (design decision 15).
 * That is what makes a settled conversion auditable: the figure an operator was shown when
 * they requested the Transfer is the figure it settles at, whatever the market did while it
 * was pending. Both columns are null for a same-currency Transfer, which asked no provider
 * anything.
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
 * <p>No timestamp for the end of the lifecycle, for the same reason: the status is the
 * single answer to whether a Transfer is done, and the ticket that first needs to know
 * <em>when</em> it finished can add that column with a reader to shape it. The two
 * timestamps here record when the Transfer was requested and when its rate was quoted,
 * which are both facts about its beginning.
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

	/**
	 * Ten decimal places, which is finer than any quote this application takes — the
	 * stand-in provider quotes six — and coarse enough to be exact in a {@code numeric}.
	 * The precision has to be stated for {@code validate} to agree with the column the
	 * migration wrote.
	 */
	@Column(precision = 20, scale = 10)
	private @Nullable BigDecimal exchangeRate;

	private @Nullable Instant exchangeRateFetchedAt;

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
	 * with both amounts and the Exchange Rate between them already resolved, because the
	 * rate is locked at request time (design decision 15).
	 *
	 * <p>The four figures arrive as one {@link ConvertedAmounts} rather than as four
	 * parameters because they are one fact: the credited amount is the debited amount at
	 * that rate, and a constructor taking them separately is one whose callers can pass a
	 * rate that does not relate its own two amounts.
	 *
	 * <p>The instant is a parameter rather than an {@code Instant.now()} because the
	 * deadline expiry tests against is measured from it, and a clock a test can drive is
	 * the difference between asserting expiry and sleeping through it.
	 *
	 * <p>Package-private, where the class is public: {@link FundsReservation} is the only
	 * thing that may bring a Transfer into being — {@code
	 * NothingButTheReservationCreatesATransferTest} is the rule stated as a test — and a
	 * parameter type that is itself package-private would make a public constructor
	 * uncallable anyway. Other packages read a Transfer; they do not make one.
	 */
	Transfer(Long sourceAccountId, Long destinationAccountId,
			ConvertedAmounts amounts, Instant requestedAt) {
		Objects.requireNonNull(amounts, "a transfer moves an amount");
		this.sourceAccountId = Objects.requireNonNull(sourceAccountId, "a transfer leaves an account");
		this.destinationAccountId = Objects.requireNonNull(destinationAccountId, "a transfer arrives somewhere");
		this.debitedAmount = amounts.debitedAmount();
		this.creditedAmount = amounts.creditedAmount();
		this.exchangeRate = amounts.exchangeRate();
		this.exchangeRateFetchedAt = amounts.exchangeRateFetchedAt();
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

	/**
	 * Destination Currency units per one of the source's, as the provider quoted it when
	 * this Transfer was requested, and the figure it will settle at however long it stays
	 * {@code PENDING} (design decision 15).
	 *
	 * @return the rate, or {@code null} for a Transfer between two Accounts in one Currency,
	 *         which needed no quote and made no provider call
	 */
	public @Nullable BigDecimal getExchangeRate() {
		return exchangeRate;
	}

	/**
	 * When this application received that quote — not when the market set it, which no
	 * provider here tells us. Null with the rate it belongs to.
	 */
	public @Nullable Instant getExchangeRateFetchedAt() {
		return exchangeRateFetchedAt;
	}

	public TransferStatus getStatus() {
		return status;
	}

	/** When the Transfer was requested. The Transactions list is ordered on it. */
	public Instant getCreatedAt() {
		return createdAt;
	}
}
