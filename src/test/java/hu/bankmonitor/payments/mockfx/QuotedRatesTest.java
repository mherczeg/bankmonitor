package hu.bankmonitor.payments.mockfx;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The table the stand-in provider quotes from, as a pure lookup with no HTTP around it.
 *
 * <p>What is worth asserting here is not the numbers — they are invented — but that the
 * table cannot contradict itself. A provider whose {@code EUR/HUF} and {@code USD/HUF}
 * disagreed with its own {@code EUR/USD} would send anyone reading a converted amount
 * looking for a rounding bug in {@code CurrencyConversion} that is really a fixture bug
 * here.
 */
class QuotedRatesTest {

	private static final Offset<BigDecimal> WITHIN_THE_QUOTED_PRECISION = Offset.offset(new BigDecimal("0.0001"));

	@Test
	@DisplayName("a currency against itself is quoted at one")
	void quotesACurrencyAgainstItselfAtOne() {
		assertThat(QuotedRates.between("EUR", "EUR").orElseThrow())
				.isEqualByComparingTo(BigDecimal.ONE);
	}

	@Test
	@DisplayName("every supported currency quotes against every other")
	void quotesEverySupportedPair() {
		for (String base : QuotedRates.SUPPORTED_CURRENCIES) {
			for (String quote : QuotedRates.SUPPORTED_CURRENCIES) {
				assertThat(QuotedRates.between(base, quote)).as("%s/%s", base, quote).isPresent();
			}
		}
	}

	/**
	 * True by construction rather than by six numbers having been typed carefully, which
	 * is the reason the table is a pivot and not a list of pairs.
	 */
	@Test
	@DisplayName("a cross rate agrees with the two rates it is crossed from")
	void crossRatesAgreeWithThePairsTheyAreDerivedFrom() {
		BigDecimal euroToDollar = QuotedRates.between("EUR", "USD").orElseThrow();
		BigDecimal dollarToForint = QuotedRates.between("USD", "HUF").orElseThrow();
		BigDecimal euroToForint = QuotedRates.between("EUR", "HUF").orElseThrow();

		assertThat(euroToDollar.multiply(dollarToForint)).isCloseTo(euroToForint, WITHIN_THE_QUOTED_PRECISION);
	}

	@Test
	@DisplayName("a pair and its inverse multiply back to one")
	void inversePairsMultiplyBackToOne() {
		BigDecimal forward = QuotedRates.between("USD", "HUF").orElseThrow();
		BigDecimal back = QuotedRates.between("HUF", "USD").orElseThrow();

		assertThat(forward.multiply(back)).isCloseTo(BigDecimal.ONE, WITHIN_THE_QUOTED_PRECISION);
	}

	/** A currency this provider does not quote is not one it invents a rate for. */
	@Test
	@DisplayName("a currency outside the table is quoted at nothing")
	void quotesNothingForACurrencyOutsideTheTable() {
		assertThat(QuotedRates.between("EUR", "GBP")).isEmpty();
		assertThat(QuotedRates.between("GBP", "EUR")).isEmpty();
	}

	/** ISO codes are uppercase, and this provider matches them exactly rather than kindly. */
	@Test
	@DisplayName("a lowercase code is not the currency it names")
	void doesNotMatchALowercaseCode() {
		assertThat(QuotedRates.between("eur", "huf")).isEmpty();
	}
}
