package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.testsupport.BootedApplicationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo Accounts of design decision 29, seen the way a reviewer sees them: by starting
 * the application under the {@code dev} profile and asking it what accounts there are.
 *
 * <p>A second application context, and deliberately so — a profile is part of a context's
 * cache key, so proving that {@code dev} seeds anything cannot be done inside the context
 * every other test shares. That is the whole cost of the ticket's fourth criterion, and it
 * buys the only assertion that exercises the runner, the repository, the service and the
 * JSON shape together.
 *
 * <p>On a database of its own. {@code jdbc:h2:mem:payments} is held open across the suite
 * by {@code DB_CLOSE_DELAY=-1}, so seeding into it would put demo rows in front of every
 * other test that reads the accounts table.
 */
@ActiveProfiles(DemoAccountSeeder.DEV_PROFILE)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:demo-accounts-seed")
class DemoAccountsSeedTheDevProfileTest extends BootedApplicationTest {

	@Test
	@DisplayName("the dev profile starts with demo Accounts in more than one Currency")
	void seedsDemoAccountsInMoreThanOneCurrency() {
		List<AccountResponse> accounts = listAccounts();

		assertThat(accounts).isNotEmpty();
		assertThat(accounts.stream().map(AccountResponse::currency).distinct())
				.as("the currencies the demo accounts are held in")
				.hasSizeGreaterThanOrEqualTo(2)
				.isSubsetOf(Currency.values());
	}

	/**
	 * A demo account holding nothing would list, satisfying the criterion above, and would
	 * still be useless to the first person who tries to move money out of it.
	 */
	@Test
	@DisplayName("every demo Account has money in it")
	void seedsAccountsWithMoneyInThem() {
		assertThat(listAccounts()).allSatisfy(account ->
				assertThat(account.balanceMinorUnits())
						.as("balance of account %d", account.id())
						.isPositive());
	}

	private List<AccountResponse> listAccounts() {
		return client().get().uri("/api/accounts")
				.exchange()
				.expectStatus().isOk()
				.returnResult(new ParameterizedTypeReference<List<AccountResponse>>() {})
				.getResponseBody();
	}
}
