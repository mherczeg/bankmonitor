package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.ProblemType;
import hu.bankmonitor.testsupport.ScriptedExchangeRates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Transfer between two Accounts denominated differently, over a real socket: what each side
 * comes to, and the quote that relates them.
 *
 * <p>The figures are checkable by hand and deliberately so. {@code 100.50 EUR} at {@code 390}
 * is {@code 39 195 HUF} — a Transfer whose two amounts are neither equal nor a hundred times
 * each other, so the scale shift {@code CurrencyConversion} applies between a Currency written
 * with two decimal places and one written with none has to be right for the assertion to pass.
 * A version that dropped the shift would credit {@code 3 919 500}, and a version that applied
 * it backwards {@code 391}.
 *
 * <p>The rate is served by {@link ScriptedExchangeRates} rather than by the stand-in provider
 * under the {@code mock-fx} profile, which picks a rate of its own and fails at a configured
 * probability: neither is something a credited figure can be asserted against.
 *
 * <p>The three claims beside the conversion are here rather than in classes of their own
 * because each is the same claim seen from an edge — a Transfer that needs no quote, and one
 * whose quote makes it not worth making. {@code TheRateIsFetchedWithNoTransactionOpenTest}
 * covers <em>where</em> the fetch happens, which is the one property of it that a response
 * cannot show.
 */
class ACrossCurrencyTransferConvertsAtTheRateItWasQuotedTest extends ScriptedRateScenario {

	private static final long A_EURO_ACCOUNT = 1L;

	private static final long THE_FORINT_ACCOUNT = 2L;

	/** Where a same-Currency Transfer goes, and where the HUF Account's one fillér would have. */
	private static final long A_SECOND_EURO_ACCOUNT = 3L;

	private static final long EUR_BALANCE = 1_000_00L;

	private static final long HUF_BALANCE = 500_000L;

	private static final long AMOUNT = 100_50L;

	private static final String RATE = "390";

	private static final long CONVERTED = 39_195L;

	/**
	 * Forint per euro read the other way round, which is what makes a single fillér worth
	 * about a quarter of a eurocent — and therefore nothing at all once it is rounded to a
	 * whole one.
	 */
	private static final String RATE_INTO_EUR = "0.0026";

	private static final String KEY = "0d1f6c1e-6b0a-4a5f-9f1a-2c3d4e5f6a7b";

	@BeforeEach
	void openTwoEuroAccountsAndAForintOne() {
		rates.quoteAt(RATE);
		openAccount(A_EURO_ACCOUNT, EUR_BALANCE, Currency.EUR);
		openAccount(THE_FORINT_ACCOUNT, HUF_BALANCE, Currency.HUF);
		openAccount(A_SECOND_EURO_ACCOUNT, EUR_BALANCE, Currency.EUR);
	}

	/**
	 * The ticket's first line: the destination is credited in its own Currency, and the source
	 * is debited in its own. Both halves matter — a conversion applied to the debited side as
	 * well would take {@code 39 195} out of a euro Account.
	 *
	 * <p>Nothing is credited yet, and the destination's untouched balance says so. A requested
	 * Transfer reserves on the source and moves no money at all; the converted figure is what
	 * settlement will pay, which is why it is worth recording now and worth locking the rate
	 * for.
	 */
	@Test
	@DisplayName("the destination is credited the converted amount, in its own Currency")
	void convertsTheAmountIntoTheDestinationAccountsCurrency() {
		TransferResponse requested = requestTransfer(KEY, A_EURO_ACCOUNT, THE_FORINT_ACCOUNT, AMOUNT)
				.expectStatus().isCreated()
				.expectBody(TransferResponse.class).returnResult().getResponseBody();

		assertThat(requested.debitedAmountMinorUnits()).isEqualTo(AMOUNT);
		assertThat(requested.debitedAmountCurrency()).isEqualTo(Currency.EUR);
		assertThat(requested.creditedAmountMinorUnits()).isEqualTo(CONVERTED);
		assertThat(requested.creditedAmountCurrency()).isEqualTo(Currency.HUF);

		assertThat(rates.pairsAsked()).containsExactly("EUR/HUF");
		assertThat(reservedAmountOf(A_EURO_ACCOUNT))
				.as("the source is held its own Currency's figure, not the destination's")
				.isEqualTo(AMOUNT);
		assertThat(balanceOf(THE_FORINT_ACCOUNT))
				.as("a requested Transfer reserves; settlement is what credits")
				.isEqualTo(HUF_BALANCE);
	}

	/**
	 * Design decision 15's whole point, end to end: the quote is written to the row, not merely
	 * used to work out a figure and then dropped. A Transfer that reported the right credited
	 * amount and stored no rate would settle correctly and be unauditable — nobody could tell
	 * afterwards whether {@code 39 195} was the rate of the day or a typo.
	 *
	 * <p>Compared with {@code isEqualByComparingTo} because the column is
	 * {@code numeric(20, 10)}: what goes in as {@code 390} comes back as
	 * {@code 390.0000000000}, which is the same rate and a different {@code BigDecimal}.
	 */
	@Test
	@DisplayName("the rate and the moment it was quoted are stored on the Transfer and reported")
	void storesTheQuoteOnTheTransferAndReportsItBack() {
		TransferResponse requested = requestTransfer(KEY, A_EURO_ACCOUNT, THE_FORINT_ACCOUNT, AMOUNT)
				.expectStatus().isCreated()
				.expectBody(TransferResponse.class).returnResult().getResponseBody();

		assertThat(requested.exchangeRate()).isEqualByComparingTo(RATE);
		assertThat(requested.exchangeRateFetchedAt()).isEqualTo(ScriptedExchangeRates.QUOTED_AT);

		assertThat(storedRateOf(requested.id())).isEqualByComparingTo(RATE);
		assertThat(storedQuoteTimeOf(requested.id())).isEqualTo(ScriptedExchangeRates.QUOTED_AT);
	}

	/**
	 * The provider is never reached, which is the claim rather than a side effect of one: an
	 * outage at somebody else's server must not stop a Transfer between two Accounts that need
	 * nothing from it. {@link ScriptedExchangeRates} is a witness here rather than a stub — it
	 * would have answered, and the assertion is that it was not asked.
	 *
	 * <p>The absent rate is the other half. A rate of one would read as a quote, and no quote
	 * was fetched.
	 *
	 * <p><b>Absence is asserted against the raw body</b>, because
	 * {@code jsonPath().doesNotExist()} cannot tell a missing member from a present null one and
	 * passes on both — which is the entire difference {@code @JsonInclude(NON_NULL)} on
	 * {@link TransferResponse} makes. One substring covers both members: the longer name begins
	 * with the shorter.
	 */
	@Test
	@DisplayName("a Transfer between two Accounts in one Currency asks the provider nothing")
	void neverPricesATransferThatNeedsNoRate() {
		byte[] body = requestTransfer(KEY, A_EURO_ACCOUNT, A_SECOND_EURO_ACCOUNT, AMOUNT)
				.expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.creditedAmountMinorUnits").isEqualTo(AMOUNT)
				.jsonPath("$.creditedAmountCurrency").isEqualTo("EUR")
				.returnResult().getResponseBody();

		assertThat(new String(requireNonNull(body), StandardCharsets.UTF_8)).doesNotContain("exchangeRate");
		assertThat(rates.pairsAsked()).isEmpty();
		assertThat(transferRows()).singleElement()
				.satisfies(row -> assertThat(row.get("EXCHANGE_RATE")).isNull());
	}

	/**
	 * No Transfer may debit the source and credit nothing, so an amount worth less than half a
	 * Minor Unit of the destination Currency is refused rather than rounded away. One fillér
	 * into a euro Account is the smallest case there is, and the one an operator will actually
	 * meet.
	 *
	 * <p>The refusal is decided in phase two, above the lock, so what it must not leave behind
	 * is everything phase three writes. The source Account here holds far more than a fillér:
	 * the funds are there, and the conversion is the only reason this Transfer does not happen.
	 *
	 * <p>The key is left {@code FAILED} for the same reason every other refusal leaves it so —
	 * a client that fixes the amount resends rather than having to invent a new key.
	 */
	@Test
	@DisplayName("an amount that converts to nothing is refused, and nothing is written")
	void refusesAConversionThatRoundsToZeroAndWritesNothing() {
		rates.quoteAt(RATE_INTO_EUR);

		requestTransfer(KEY, THE_FORINT_ACCOUNT, A_SECOND_EURO_ACCOUNT, 1L)
				.expectStatus().isEqualTo(422)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.type").isEqualTo(ProblemType.CONVERSION_ROUNDS_TO_ZERO.urn())
				.jsonPath("$.debitedAmountMinorUnits").isEqualTo(1)
				.jsonPath("$.debitedAmountCurrency").isEqualTo("HUF")
				.jsonPath("$.destinationCurrency").isEqualTo("EUR")
				.jsonPath("$.exchangeRate").value((Number quoted) ->
						assertThat(new BigDecimal(quoted.toString())).isEqualByComparingTo(RATE_INTO_EUR));

		assertThat(transferRows()).isEmpty();
		assertThat(checkLedgerRows()).isEmpty();
		assertThat(reservedAmountOf(THE_FORINT_ACCOUNT)).isZero();
		assertThat(claimedStatus(KEY)).isEqualTo("FAILED");
	}

	private BigDecimal storedRateOf(long transferId) {
		return database.queryForObject(
				"SELECT exchange_rate FROM transfers WHERE id = ?", BigDecimal.class, transferId);
	}

	/**
	 * Read as an {@link OffsetDateTime} because that is what the column is — {@code timestamp
	 * with time zone} — and turned into the instant the assertion is about, which is the
	 * comparison that holds whatever offset the driver hands the value back in.
	 */
	private Instant storedQuoteTimeOf(long transferId) {
		return database.queryForObject("SELECT exchange_rate_fetched_at FROM transfers WHERE id = ?",
				OffsetDateTime.class, transferId).toInstant();
	}
}
