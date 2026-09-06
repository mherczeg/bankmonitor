package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Accounts to look at, for anyone who has just started the application and wants to see it
 * do something.
 *
 * <p><b>Seed data is not schema</b> (design decision 29). The obvious home for these rows
 * is a Flyway migration, and it is the wrong one: a migration runs everywhere the schema
 * does, so demo accounts in one would arrive in the test database too and every test that
 * reads the accounts table would start from someone else's fixtures.
 * {@code FlywayOwnsTheSchemaTest} holds both halves of that — no migration inserts rows,
 * and no runner writes any outside {@link #DEV_PROFILE}.
 *
 * <p>All three currencies, and two accounts in each of two of them, so that a transfer
 * between accounts of one currency and a cross-currency one — refused with {@code 422}
 * until the Exchange Rate arrives — are both available to try without creating anything
 * first. HUF is here for a reason beyond
 * variety: its Minor Units are not hundredths, so a display that divides by a hundred
 * regardless is visibly wrong on the accounts screen rather than off by a factor nobody
 * notices.
 *
 * <p>Every account starts with nothing reserved, because raising a Reserved Amount is
 * ticket 13's reservation and the entity offers no other way to. The Available Balance a
 * seeded account reports therefore equals its balance until a Transfer commits some of it.
 */
@Component
@Profile(DemoAccountSeeder.DEV_PROFILE)
class DemoAccountSeeder implements CommandLineRunner {

	/** Not active in the test suite, and not active in a packaged application either. */
	static final String DEV_PROFILE = "dev";

	private static final Logger log = LoggerFactory.getLogger(DemoAccountSeeder.class);

	private static final List<Money> OPENING_BALANCES = List.of(
			new Money(2_500_00L, Currency.EUR),
			new Money(100_00L, Currency.EUR),
			new Money(1_000_00L, Currency.USD),
			new Money(1_000_000L, Currency.HUF),
			new Money(250_000L, Currency.HUF));

	private final AccountRepository accounts;

	DemoAccountSeeder(AccountRepository accounts) {
		this.accounts = accounts;
	}

	@Override
	public void run(String... args) {
		List<Account> seeded = accounts.saveAll(OPENING_BALANCES.stream().map(Account::new).toList());

		log.info("Seeded {} demo accounts for the '{}' profile", seeded.size(), DEV_PROFILE);
	}
}
