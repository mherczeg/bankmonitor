package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.testsupport.BootedApplicationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/accounts}: every Account, with what it holds, what is spoken for and
 * what is left to spend.
 *
 * <p>Through the running server rather than a {@code @WebMvcTest} with a mocked service,
 * because the claim worth making is about a figure that <em>no layer stores</em>. The
 * Available Balance is derived from a row the entity has no way to write — raising the
 * Reserved Amount is ticket 13's reservation — so a mocked service would have to be handed
 * an {@link Account} that cannot exist, and would then assert the mapping against a value
 * the test author chose. Reaching it needs a real row, so the rows here go in as SQL, as
 * they do in {@link AccountHoldsTwoFiguresInOneCurrencyTest}.
 *
 * <p>The context is the one {@link BootedApplicationTest} already boots for the suite, so
 * the reach costs nothing. The price is that this class writes to a database other tests
 * share, which {@link #emptyTheAccountsTable()} pays back after every method.
 */
class AccountListingTest extends BootedApplicationTest {

	private static final String LISTING = "/api/accounts";

	private static final String OPENAPI_DOCUMENT = "/v3/api-docs";

	@Autowired
	private JdbcTemplate jdbc;

	@AfterEach
	void emptyTheAccountsTable() {
		jdbc.update("DELETE FROM accounts");
	}

	@Test
	@DisplayName("every Account is listed with its balance and what is reserved against it")
	void listsEveryAccountWithItsBalanceAndWhatIsReserved() {
		insertAccount(1L, 2_500_00L, "EUR", 400_00L);
		insertAccount(2L, 1_000_00L, "USD", 0L);

		client().get().uri(LISTING)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.length()").isEqualTo(2)
				.jsonPath("$[0].id").isEqualTo(1)
				.jsonPath("$[0].currency").isEqualTo("EUR")
				.jsonPath("$[0].balanceMinorUnits").isEqualTo(2_500_00L)
				.jsonPath("$[0].reservedAmountMinorUnits").isEqualTo(400_00L)
				.jsonPath("$[0].availableBalanceMinorUnits").isEqualTo(2_100_00L)
				.jsonPath("$[1].id").isEqualTo(2)
				.jsonPath("$[1].currency").isEqualTo("USD")
				.jsonPath("$[1].balanceMinorUnits").isEqualTo(1_000_00L)
				.jsonPath("$[1].reservedAmountMinorUnits").isEqualTo(0)
				.jsonPath("$[1].availableBalanceMinorUnits").isEqualTo(1_000_00L);
	}

	/**
	 * The reason design decision 13 keeps two figures rather than three: the Available
	 * Balance is computed on the way out, so no write can leave it disagreeing with the two
	 * it is derived from.
	 *
	 * <p>HUF, because its Minor Units are not hundredths — a figure that had picked up a
	 * stray division by a hundred somewhere between the column and the wire would still
	 * subtract correctly in EUR, and would not here.
	 */
	@Test
	@DisplayName("the Available Balance is the balance less what is reserved against it")
	void reportsAvailableBalanceAsTheBalanceLessTheReservedAmount() {
		insertAccount(1L, 90_000L, "HUF", 25_000L);

		client().get().uri(LISTING)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$[0].balanceMinorUnits").isEqualTo(90_000L)
				.jsonPath("$[0].reservedAmountMinorUnits").isEqualTo(25_000L)
				.jsonPath("$[0].availableBalanceMinorUnits").isEqualTo(65_000L);
	}

	/**
	 * An unordered listing would let the accounts screen reshuffle itself between two
	 * refetches of unchanged data. The rows go in scrambled so that a query which happened
	 * to return insertion order would not pass by luck.
	 */
	@Test
	@DisplayName("the listing comes back in a stable order")
	void listsAccountsInAStableOrder() {
		insertAccount(3L, 300L, "EUR", 0L);
		insertAccount(1L, 100L, "EUR", 0L);
		insertAccount(2L, 200L, "EUR", 0L);

		client().get().uri(LISTING)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$[0].id").isEqualTo(1)
				.jsonPath("$[1].id").isEqualTo(2)
				.jsonPath("$[2].id").isEqualTo(3);
	}

	/** An empty database is an empty list, not a 404: the collection exists and is empty. */
	@Test
	@DisplayName("no accounts is an empty list rather than a missing one")
	void listsNothingWhenThereAreNoAccounts() {
		client().get().uri(LISTING)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$").isArray()
				.jsonPath("$.length()").isEqualTo(0);
	}

	/**
	 * The first shape in the document ticket 32 generates the frontend's types from, and so
	 * the first one whose field names are a build-time contract rather than a convention. A
	 * rename here becomes a TypeScript compile error there, which is the entire point of
	 * design decision 26 — but only for fields the document actually describes.
	 */
	@Test
	@DisplayName("the OpenAPI document describes the listing and its response shape")
	void describesTheListingInTheOpenApiDocument() {
		client().get().uri(OPENAPI_DOCUMENT)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.paths['" + LISTING + "'].get").exists()
				.jsonPath("$.components.schemas.AccountResponse.properties.id").exists()
				.jsonPath("$.components.schemas.AccountResponse.properties.currency.enum")
				.value((List<String> currencies) ->
						assertThat(currencies).containsExactlyInAnyOrder("EUR", "USD", "HUF"))
				.jsonPath("$.components.schemas.AccountResponse.properties.balanceMinorUnits").exists()
				.jsonPath("$.components.schemas.AccountResponse.properties.reservedAmountMinorUnits").exists()
				.jsonPath("$.components.schemas.AccountResponse.properties.availableBalanceMinorUnits").exists();
	}

	/**
	 * With an explicit identifier, which the identity column accepts because it is declared
	 * {@code generated by default}. Assertions that name the row they are about read better
	 * than assertions about whichever value the sequence had reached.
	 */
	private void insertAccount(long id, long balanceMinorUnits, String currency, long reservedMinorUnits) {
		jdbc.update("""
				INSERT INTO accounts (id, balance_minor_units, balance_currency,
				                      reserved_amount_minor_units, reserved_amount_currency)
				VALUES (?, ?, ?, ?, ?)
				""", id, balanceMinorUnits, currency, reservedMinorUnits, currency);
	}
}
