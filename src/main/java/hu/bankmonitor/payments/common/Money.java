package hu.bankmonitor.payments.common;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;

/**
 * An amount of money: a whole count of {@link Currency}'s Minor Units, paired with the
 * currency it is denominated in.
 *
 * <p>There is no bare amount in this context. A number on its own is not money, which is
 * why the currency is a component of the value rather than a column beside it.
 */
@Embeddable
public record Money(
		long minorUnits,

		// Left unannotated, JPA stores the currency by ordinal. See design decision 29.
		@Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) Currency currency) {

	public Money {
		Objects.requireNonNull(currency, "an amount with no currency is not money");
	}

	/** No money at all, in a stated currency — a new Account's Reserved Amount, for one. */
	public static Money zero(Currency currency) {
		return new Money(0L, currency);
	}

	/**
	 * @throws IllegalArgumentException if the addend is in a different currency
	 * @throws ArithmeticException if the sum does not fit in a {@code long}
	 */
	public Money plus(Money addend) {
		return new Money(Math.addExact(minorUnits, minorUnitsMatching(addend)), currency);
	}

	/**
	 * @throws IllegalArgumentException if the subtrahend is in a different currency
	 * @throws ArithmeticException if the difference does not fit in a {@code long}
	 */
	public Money minus(Money subtrahend) {
		return new Money(Math.subtractExact(minorUnits, minorUnitsMatching(subtrahend)), currency);
	}

	/**
	 * Whether this is strictly the smaller amount. An Available Balance equal to the
	 * amount requested is enough to cover it, so the overdraft check reads
	 * {@code available.isLessThan(amount)}.
	 *
	 * <p>Deliberately a named method rather than {@link Comparable}, whose {@code compareTo}
	 * has to be total and so could not refuse a cross-currency comparison.
	 *
	 * @throws IllegalArgumentException if the other amount is in a different currency
	 */
	public boolean isLessThan(Money other) {
		return minorUnits < minorUnitsMatching(other);
	}

	/**
	 * The other amount's Minor Units, once it is established that they count the same
	 * thing as this one's.
	 *
	 * <p>Mixing currencies is a bug in the caller rather than something a user asked for,
	 * so it is an unchecked exception and nothing catches it: two amounts meet only after
	 * a conversion has made them one currency.
	 */
	private long minorUnitsMatching(Money other) {
		if (currency != other.currency) {
			throw new IllegalArgumentException(
					"%s and %s are different currencies; one has to be converted first"
							.formatted(currency, other.currency));
		}
		return other.minorUnits;
	}
}
