package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * A balance held in one currency, and the part of it already committed to transfers that
 * have not finished.
 *
 * <p>The atomic entity of this context: nothing owns an Account. {@code User} is named in
 * {@code CONTEXT.md} as deliberately unmodelled, and design decision 1 has why.
 *
 * <p><b>Two figures rather than one</b>, per design decision 13. The balance is what the
 * Account holds; the Reserved Amount is what is spoken for. Their difference is the
 * {@linkplain #getAvailableBalance() Available Balance}, which is what an overdraft check
 * tests against — so an Account's own row answers "how much of this is still spendable"
 * without reading a single transfer. Reserving raises the Reserved Amount, settling lowers
 * both, and rejection or expiry lowers only the Reserved Amount.
 *
 * <p>The currency is fixed at creation and there is no way to change it: an Account that
 * could be redenominated would make every historical balance ambiguous. Both figures carry
 * it because both are {@link Money}, and {@link #getCurrency()} is the single answer they
 * are constructed to agree on.
 */
@Entity
@Table(name = "accounts")
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Embedded
	@AttributeOverride(name = "minorUnits", column = @Column(name = "balance_minor_units"))
	@AttributeOverride(name = "currency", column = @Column(name = "balance_currency"))
	private Money balance;

	@Embedded
	@AttributeOverride(name = "minorUnits", column = @Column(name = "reserved_amount_minor_units"))
	@AttributeOverride(name = "currency", column = @Column(name = "reserved_amount_currency"))
	private Money reservedAmount;

	protected Account() {
		// required by JPA
	}

	/**
	 * Opens an Account holding the given amount, with nothing reserved against it. The
	 * opening balance carries the currency, so there is no way to name one that disagrees
	 * with the other figure.
	 */
	public Account(Money openingBalance) {
		this.balance = Objects.requireNonNull(openingBalance, "an account opens with a balance");
		this.reservedAmount = Money.zero(openingBalance.currency());
	}

	public Long getId() {
		return id;
	}

	/** Everything the Account holds, including the part already spoken for. */
	public Money getBalance() {
		return balance;
	}

	/** The part of the balance committed to transfers that have not reached a terminal state. */
	public Money getReservedAmount() {
		return reservedAmount;
	}

	/** What the Account can still spend, and the figure an overdraft check tests against. */
	public Money getAvailableBalance() {
		return balance.minus(reservedAmount);
	}

	/** Fixed when the Account was opened. Both figures are denominated in it. */
	public Currency getCurrency() {
		return balance.currency();
	}

	/**
	 * Commits part of the Available Balance to a Transfer that has not settled: the Reserved
	 * Amount rises by the amount, the balance does not move, and nothing is credited
	 * anywhere. Settling the Transfer lowers both figures; rejecting or expiring it lowers
	 * only the Reserved Amount.
	 *
	 * <p>The refusal lives here rather than in the caller, so that there is no way to reach a
	 * balance and overdraw it. What does <em>not</em> live here is whether it was safe to
	 * ask: this method compares two figures it was handed, and that the Account's row is
	 * locked while it does so is the caller's obligation — design decision 6 is why. Called
	 * against an unlocked Account the arithmetic is still right and the answer is still
	 * worthless, because another transaction may have spent the same Available Balance in
	 * between.
	 *
	 * @throws InsufficientFundsException if the Available Balance does not cover the amount
	 * @throws IllegalArgumentException   if the amount is in another Currency
	 */
	public void reserve(Money amount) {
		Money availableBalance = getAvailableBalance();
		if (availableBalance.isLessThan(amount)) {
			throw new InsufficientFundsException(availableBalance, amount);
		}
		reservedAmount = reservedAmount.plus(amount);
	}

	/**
	 * Pays a Transfer this Account had reserved for: the balance falls by the amount and the
	 * Reserved Amount falls with it, because the reservation is consumed rather than left
	 * behind. The Available Balance therefore does not move — the money was already spoken
	 * for, and settling is where it actually leaves.
	 *
	 * <p><b>Both figures, and that is the whole reason this is one method.</b> A debit
	 * without the matching consumption would pass every balance assertion and leave the
	 * Account holding a reservation for a Transfer that is finished, so every later overdraft
	 * check would test against a figure short by it, for ever.
	 *
	 * @throws IllegalArgumentException if the amount exceeds the Reserved Amount, or is in
	 *                                  another Currency
	 */
	public void settle(Money amount) {
		reservedAmount = reservedAmount.minus(withinTheReservedAmount(amount));
		balance = balance.minus(amount);
	}

	/**
	 * Gives up a reservation without moving money, which is what all three of rejection,
	 * expiry and any later cancellation do: the Reserved Amount falls, the balance does not,
	 * and the Available Balance rises back to where it was before the Transfer was requested.
	 *
	 * @throws IllegalArgumentException if the amount exceeds the Reserved Amount, or is in
	 *                                  another Currency
	 */
	public void release(Money amount) {
		reservedAmount = reservedAmount.minus(withinTheReservedAmount(amount));
	}

	/**
	 * Receives a settled Transfer. Nothing is reserved on the receiving side — the money
	 * arrives owing nobody anything — so the balance and the Available Balance rise together,
	 * and there is no figure this could be asked to overdraw.
	 *
	 * @throws IllegalArgumentException if the amount is in another Currency
	 */
	public void credit(Money amount) {
		balance = balance.plus(amount);
	}

	/**
	 * The one refusal both outward movements share: neither may take the Reserved Amount
	 * below zero. An Account that could would go on to report an Available Balance larger
	 * than it holds, and the next reservation would overdraw against the excess.
	 *
	 * <p>Reaching it means a Transfer settled or was released twice, or against a reservation
	 * another Transfer had already consumed — a caller's mistake rather than a request an
	 * operator made, which is why it is not an {@link InsufficientFundsException}.
	 */
	private Money withinTheReservedAmount(Money amount) {
		if (reservedAmount.isLessThan(amount)) {
			throw new IllegalArgumentException(
					"reserved amount %s does not cover %s".formatted(reservedAmount, amount));
		}
		return amount;
	}
}
