package hu.bankmonitor.payments.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

	@Nested
	@DisplayName("equality")
	class Equality {

		/**
		 * The property design decision 16 chose {@code long} to get. {@code BigDecimal}
		 * compares scale in {@code equals}, so the same amount written two ways compares
		 * unequal; a count of Minor Units has no scale to disagree about.
		 */
		@Test
		@DisplayName("two amounts of the same money are equal")
		void comparesTheCountAndTheCurrency() {
			assertThat(new Money(100_50L, Currency.EUR)).isEqualTo(new Money(100_50L, Currency.EUR));
		}

		@Test
		@DisplayName("the same count in a different currency is not the same money")
		void separatesAmountsInDifferentCurrencies() {
			assertThat(new Money(100L, Currency.EUR)).isNotEqualTo(new Money(100L, Currency.HUF));
		}
	}

	@Nested
	@DisplayName("arithmetic")
	class Arithmetic {

		@Test
		@DisplayName("adding counts Minor Units, keeping the currency")
		void adds() {
			Money sum = new Money(100_50L, Currency.EUR).plus(new Money(24_99L, Currency.EUR));

			assertThat(sum).isEqualTo(new Money(125_49L, Currency.EUR));
		}

		@Test
		@DisplayName("subtracting counts Minor Units, keeping the currency")
		void subtracts() {
			Money difference = new Money(125_49L, Currency.EUR).minus(new Money(24_99L, Currency.EUR));

			assertThat(difference).isEqualTo(new Money(100_50L, Currency.EUR));
		}

		/**
		 * A balance may not go negative, but that is the Account's invariant and the
		 * Account enforces it. A difference between two amounts is money too, and
		 * refusing to represent it here would only push the subtraction out to bare
		 * {@code long}s where nothing checks the currency.
		 */
		@Test
		@DisplayName("a difference may be negative")
		void subtractsPastZero() {
			Money difference = new Money(100L, Currency.HUF).minus(new Money(250L, Currency.HUF));

			assertThat(difference).isEqualTo(new Money(-150L, Currency.HUF));
		}

		@Test
		@DisplayName("zero is an amount like any other, in a stated currency")
		void hasAZeroPerCurrency() {
			assertThat(Money.zero(Currency.USD)).isEqualTo(new Money(0L, Currency.USD));
		}

		/**
		 * Java's {@code +} wraps silently on overflow, which would turn the largest
		 * possible balance into a negative one — the single way a count of Minor Units
		 * can represent a quantity that is not the answer.
		 */
		@Test
		@DisplayName("an addition that will not fit in a long is refused rather than wrapped")
		void refusesToOverflow() {
			Money theLargestAmountThereIs = new Money(Long.MAX_VALUE, Currency.HUF);

			assertThatThrownBy(() -> theLargestAmountThereIs.plus(new Money(1L, Currency.HUF)))
					.isInstanceOf(ArithmeticException.class);
		}

		@Test
		@DisplayName("a subtraction that will not fit in a long is refused rather than wrapped")
		void refusesToUnderflow() {
			Money theSmallestAmountThereIs = new Money(Long.MIN_VALUE, Currency.HUF);

			assertThatThrownBy(() -> theSmallestAmountThereIs.minus(new Money(1L, Currency.HUF)))
					.isInstanceOf(ArithmeticException.class);
		}
	}

	/**
	 * Adding euros to forints is a bug in the caller, not a request a user made, so it is
	 * an unchecked exception rather than a value some caller might forget to inspect.
	 * Nothing catches it: cross-currency amounts meet only after a conversion (ticket 07)
	 * has already made them one currency.
	 */
	@Nested
	@DisplayName("operands in differing currencies")
	class DifferingCurrencies {

		@Test
		@DisplayName("adding across currencies is refused, naming both")
		void refusesToAdd() {
			Money euros = new Money(100_00L, Currency.EUR);
			Money forints = new Money(100L, Currency.HUF);

			assertThatThrownBy(() -> euros.plus(forints))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("EUR")
					.hasMessageContaining("HUF");
		}

		@Test
		@DisplayName("subtracting across currencies is refused, naming both")
		void refusesToSubtract() {
			Money euros = new Money(100_00L, Currency.EUR);
			Money dollars = new Money(100_00L, Currency.USD);

			assertThatThrownBy(() -> euros.minus(dollars))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("EUR")
					.hasMessageContaining("USD");
		}

		@Test
		@DisplayName("comparing across currencies is refused, naming both")
		void refusesToCompare() {
			Money forints = new Money(100L, Currency.HUF);
			Money dollars = new Money(100L, Currency.USD);

			assertThatThrownBy(() -> forints.isLessThan(dollars))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("HUF")
					.hasMessageContaining("USD");
		}

		/**
		 * Without this, two amounts that have both lost their currency would combine
		 * happily, because the guard above only compares them to each other.
		 */
		@Test
		@DisplayName("a count with no currency is not money")
		void refusesToExistWithoutACurrency() {
			assertThatThrownBy(() -> new Money(100L, null)).isInstanceOf(NullPointerException.class);
		}
	}

	/**
	 * The shape the overdraft check of ticket 13 is written in:
	 * {@code availableBalance.isLessThan(amount)} refuses the transfer.
	 */
	@Nested
	@DisplayName("comparison")
	class Comparison {

		@Test
		@DisplayName("a smaller count is less")
		void ordersBySize() {
			assertThat(new Money(99_99L, Currency.EUR).isLessThan(new Money(100_00L, Currency.EUR))).isTrue();
			assertThat(new Money(100_01L, Currency.EUR).isLessThan(new Money(100_00L, Currency.EUR))).isFalse();
		}

		/** The boundary the overdraft check turns on: spending an entire balance is allowed. */
		@Test
		@DisplayName("an equal amount is not less")
		void treatsEqualAmountsAsNotLess() {
			assertThat(new Money(100_00L, Currency.EUR).isLessThan(new Money(100_00L, Currency.EUR))).isFalse();
		}

		@Test
		@DisplayName("negative amounts order below zero")
		void ordersNegativeAmounts() {
			assertThat(new Money(-1L, Currency.HUF).isLessThan(Money.zero(Currency.HUF))).isTrue();
		}
	}
}
