package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.testsupport.BootedApplicationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one test that lets the controller, the service, the repository and the migration
 * disagree: a real request over a real socket, and then the row it left behind, read in
 * SQL.
 *
 * <p>{@link AccountCreationContractTest} stubs the service away to assert the wire, and
 * {@code AccountHoldsTwoFiguresInOneCurrencyTest} asserts the mapping without an endpoint
 * in front of it. Each is green while the two halves are wired to nothing, which is what
 * this closes.
 */
class CreatedAccountReachesTheTableTest extends BootedApplicationTest {

	@Autowired
	private JdbcTemplate database;

	/**
	 * HUF, because fillér are not counted in hundredths: an amount that lost or gained a
	 * factor of a hundred anywhere between the request body and the column would not read
	 * back as the number that was sent.
	 */
	@Test
	@DisplayName("opening an Account writes it to the accounts table, with nothing reserved")
	void writesTheOpenedAccountToTheTable() {
		AccountResponse created = client().post().uri("/api/accounts")
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
						{"currency": "HUF", "openingBalanceMinorUnits": 90000}""")
				.exchange()
				.expectStatus().isCreated()
				.expectBody(AccountResponse.class)
				.returnResult()
				.getResponseBody();

		assertThat(created).isNotNull();
		assertThat(created.availableBalanceMinorUnits()).isEqualTo(90_000L);

		Map<String, Object> row = database.queryForMap("""
				SELECT balance_minor_units, balance_currency, reserved_amount_minor_units
				FROM accounts WHERE id = ?
				""", created.id());

		assertThat(row).containsEntry("BALANCE_MINOR_UNITS", 90_000L)
				.containsEntry("BALANCE_CURRENCY", "HUF")
				.containsEntry("RESERVED_AMOUNT_MINOR_UNITS", 0L);
	}
}
