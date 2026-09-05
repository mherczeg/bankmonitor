package hu.bankmonitor.payments.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The closed set of denominations this context deals in, and the one fact each of them
 * carries.
 *
 * <p>Both assertions are spec-locks rather than logic under test: adding a fourth
 * currency, or correcting a decimal count, should be a deliberate edit that a failing
 * test asks you to make, because {@link Currency#decimalPlaces()} is read at the edges
 * that parse and display money and nowhere else.
 */
class CurrencyTest {

	@Test
	@DisplayName("the set of currencies is closed to EUR, USD and HUF")
	void isClosedToThreeCurrencies() {
		assertThat(Currency.values()).containsExactlyInAnyOrder(Currency.EUR, Currency.USD, Currency.HUF);
	}

	@Test
	@DisplayName("each currency carries the number of decimal places it is written with")
	void carriesTheDecimalPlacesItIsWrittenWith() {
		assertThat(Currency.EUR.decimalPlaces()).isEqualTo(2);
		assertThat(Currency.USD.decimalPlaces()).isEqualTo(2);
		assertThat(Currency.HUF.decimalPlaces()).isZero();
	}
}
