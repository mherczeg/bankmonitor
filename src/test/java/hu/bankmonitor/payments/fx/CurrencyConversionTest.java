package hu.bankmonitor.payments.fx;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyConversionTest {

	/**
	 * Design decision 16 works this one through: {@code 10050 × 390.12 × 10^(0−2)}
	 * is {@code 39207.06}, and fillér are not counted in hundredths, so that is 39207 Ft.
	 */
	@Test
	@DisplayName("EUR to HUF shifts down two decimal places")
	void convertsEurosToForints() {
		ConversionResult result = CurrencyConversion.convert(
				new Money(100_50L, Currency.EUR), Currency.HUF, new BigDecimal("390.12"));

		assertThat(result).isEqualTo(new ConversionResult.Converted(new Money(39_207L, Currency.HUF)));
	}

	/**
	 * The same trade back the other way, so the shift is exercised in both directions:
	 * {@code 39207 × 0.002563 × 10^(2−0)} is {@code 10048.7541}, which is 10049 cents.
	 */
	@Test
	@DisplayName("HUF to EUR shifts up two decimal places")
	void convertsForintsToEuros() {
		ConversionResult result = CurrencyConversion.convert(
				new Money(39_207L, Currency.HUF), Currency.EUR, new BigDecimal("0.002563"));

		assertThat(result).isEqualTo(new ConversionResult.Converted(new Money(100_49L, Currency.EUR)));
	}

	/**
	 * Two currencies written with the same number of decimal places, so the shift is by
	 * zero and the rate is the whole of it: {@code 12345 × 0.9234} is {@code 11399.373}.
	 *
	 * <p>This is also the case a conversion that dropped the shift entirely would still
	 * pass, which is why it is not the only one here.
	 */
	@Test
	@DisplayName("USD to EUR shifts by nothing, both being written with two decimals")
	void convertsDollarsToEuros() {
		ConversionResult result = CurrencyConversion.convert(
				new Money(123_45L, Currency.USD), Currency.EUR, new BigDecimal("0.9234"));

		assertThat(result).isEqualTo(new ConversionResult.Converted(new Money(113_99L, Currency.EUR)));
	}

	/**
	 * Both halves of HALF_EVEN, because either one alone agrees with a rounding mode the
	 * design did not choose. At the rate below, 100 Ft is exactly {@code 25.5} cents and
	 * 300 Ft is exactly {@code 76.5}; HALF_EVEN takes the even neighbour each time, so
	 * one goes up and one goes down. HALF_UP would round both up, HALF_DOWN both down.
	 */
	@Test
	@DisplayName("an amount exactly half a Minor Unit either way goes to the even neighbour")
	void roundsHalvesToEven() {
		BigDecimal exchangeRate = new BigDecimal("0.00255");

		assertThat(CurrencyConversion.convert(new Money(100L, Currency.HUF), Currency.EUR, exchangeRate))
				.isEqualTo(new ConversionResult.Converted(new Money(26L, Currency.EUR)));
		assertThat(CurrencyConversion.convert(new Money(300L, Currency.HUF), Currency.EUR, exchangeRate))
				.isEqualTo(new ConversionResult.Converted(new Money(76L, Currency.EUR)));
	}

	/**
	 * The case design decision 16 refuses with a {@code 422}: 1 Ft is a quarter of a
	 * cent, and crediting the rounded nothing would debit the source account and credit
	 * the destination with an amount that is not there.
	 */
	@Test
	@DisplayName("an amount that rounds away to nothing is a distinct outcome, not zero money")
	void reportsAnAmountThatRoundsToNothing() {
		ConversionResult result = CurrencyConversion.convert(
				new Money(1L, Currency.HUF), Currency.EUR, new BigDecimal("0.00255"));

		assertThat(result).isEqualTo(new ConversionResult.RoundsToZero());
	}

	/**
	 * Beyond what the ticket asked for, and here because of what it asked for: without
	 * this, a rate of zero produces {@link ConversionResult.RoundsToZero} and the caller
	 * answers {@code 422} — telling the operator their amount was too small when what
	 * actually happened is that the provider sent a rate no money can be exchanged at.
	 * A wrong rate is a bug above this function, so it is refused the way {@code Money}
	 * refuses mixed currencies rather than reported as an outcome.
	 */
	@Test
	@DisplayName("a rate at or below zero is refused rather than rounded away to nothing")
	void refusesARateThatIsNotPositive() {
		Money amount = new Money(100_00L, Currency.EUR);

		assertThatThrownBy(() -> CurrencyConversion.convert(amount, Currency.HUF, BigDecimal.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CurrencyConversion.convert(amount, Currency.HUF, new BigDecimal("-390.12")))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
