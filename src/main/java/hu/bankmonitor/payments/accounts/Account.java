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
}
