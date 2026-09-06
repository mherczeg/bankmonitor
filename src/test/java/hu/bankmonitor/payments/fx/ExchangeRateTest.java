package hu.bankmonitor.payments.fx;

import hu.bankmonitor.payments.common.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeRateTest {

	/**
	 * Ticket 07 refuses a rate at or below zero inside {@code convert} on the grounds that
	 * it would otherwise be reported as {@code RoundsToZero} — an answer that blames the
	 * operator for a provider's bug. This is the same refusal one layer earlier, where the
	 * bad value enters the application, so the quote never reaches the arithmetic at all.
	 */
	@Test
	@DisplayName("A quote of zero is refused where it enters, not where it is applied")
	void refusesARateOfZero() {
		assertThatThrownBy(() -> new ExchangeRate(
				Currency.EUR, Currency.HUF, BigDecimal.ZERO, Instant.EPOCH))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("A negative quote is refused too")
	void refusesANegativeRate() {
		assertThatThrownBy(() -> new ExchangeRate(
				Currency.EUR, Currency.HUF, new BigDecimal("-1"), Instant.EPOCH))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
