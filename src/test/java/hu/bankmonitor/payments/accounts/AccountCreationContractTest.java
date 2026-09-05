package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import hu.bankmonitor.payments.common.ProblemType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * What an operator sends to open an Account, what comes back, and what a refusal says.
 *
 * <p>No database: {@link AccountService} is stubbed, so every assertion here is about the
 * wire — the status code, the field names, and the problem document a rejected field
 * produces. That the same request also reaches the table is
 * {@link CreatedAccountReachesTheTableTest}'s claim.
 *
 * <p>The security chain is left out rather than imported, because the claim that
 * {@code /api/**} is reachable without credentials belongs to {@code SecurityChainTest},
 * which makes it against a running server and a real filter chain.
 */
@WebMvcTest(AccountController.class)
@AutoConfigureMockMvc(addFilters = false)
class AccountCreationContractTest {

	@Autowired
	private MockMvcTester mvc;

	@MockitoBean
	private AccountService accounts;

	@Test
	@DisplayName("opening an Account answers 201 with the Account that was opened")
	void answersCreatedWithTheAccountItOpened() {
		Money opening = new Money(100_50L, Currency.EUR);
		given(accounts.open(opening)).willReturn(openedAccount(7L, opening));

		MvcTestResult result = create("""
				{"currency": "EUR", "openingBalanceMinorUnits": 10050}""");

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
		assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(7);
		assertThat(result).bodyJson().extractingPath("$.currency").isEqualTo("EUR");
		assertThat(result).bodyJson().extractingPath("$.balanceMinorUnits").isEqualTo(10050);
		assertThat(result).bodyJson().extractingPath("$.availableBalanceMinorUnits").isEqualTo(10050);
	}

	/**
	 * HUF is the currency that catches a stray division by a hundred: its Minor Units are
	 * not hundredths, so 90 000 fillér that went through one would not read back equal.
	 */
	@Test
	@DisplayName("the amount on the wire is a count of Minor Units, not a decimal quantity")
	void carriesTheAmountAsACountOfMinorUnits() {
		Money opening = new Money(90_000L, Currency.HUF);
		given(accounts.open(opening)).willReturn(openedAccount(1L, opening));

		MvcTestResult result = create("""
				{"currency": "HUF", "openingBalanceMinorUnits": 90000}""");

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.balanceMinorUnits").isEqualTo(90000);
		then(accounts).should().open(opening);
	}

	/**
	 * The field is a count, so a decimal in it is not a smaller mistake than a word would
	 * be — and Jackson's default is to truncate it, which would open a euro Account holding
	 * {@code 100} cents for an operator who typed {@code 100.50}.
	 */
	@Test
	@DisplayName("a decimal amount is refused rather than truncated to a whole number")
	void refusesADecimalAmountRatherThanTruncatingIt() {
		MvcTestResult result = create("""
				{"currency": "EUR", "openingBalanceMinorUnits": 100.50}""");

		assertThatIsAValidationProblem(result, "openingBalanceMinorUnits");
		then(accounts).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("a currency this service cannot quote is rejected against its own field")
	void rejectsAnUnquotableCurrencyAgainstItsField() {
		MvcTestResult result = create("""
				{"currency": "GBP", "openingBalanceMinorUnits": 10050}""");

		assertThatIsAValidationProblem(result, "currency");
		assertThat(result).bodyJson()
				.extractingPath("$.errors[0].message").asString()
				.contains("EUR", "USD", "HUF");
		then(accounts).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("a negative starting balance is rejected against its own field")
	void rejectsANegativeStartingBalanceAgainstItsField() {
		MvcTestResult result = create("""
				{"currency": "EUR", "openingBalanceMinorUnits": -1}""");

		assertThatIsAValidationProblem(result, "openingBalanceMinorUnits");
		then(accounts).shouldHaveNoInteractions();
	}

	/** An Account with nothing in it is a thing an operator may want; a negative one is not. */
	@Test
	@DisplayName("an Account may be opened with nothing in it")
	void opensAnAccountWithNothingInIt() {
		Money opening = Money.zero(Currency.USD);
		given(accounts.open(opening)).willReturn(openedAccount(3L, opening));

		MvcTestResult result = create("""
				{"currency": "USD", "openingBalanceMinorUnits": 0}""");

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.balanceMinorUnits").isEqualTo(0);
	}

	@Test
	@DisplayName("an empty request names both missing fields, not just the first")
	void namesEveryMissingField() {
		MvcTestResult result = create("{}");

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson()
				.extractingPath("$.errors[*].field").asInstanceOf(LIST)
				.containsExactly("currency", "openingBalanceMinorUnits");
		then(accounts).shouldHaveNoInteractions();
	}

	private MvcTestResult create(String body) {
		return mvc.post().uri("/api/accounts")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)
				.exchange();
	}

	/**
	 * The identifier is the database's to hand out, so an Account that has one is one that
	 * has been saved — a state only the repository can reach and this test has to stand in
	 * for.
	 */
	private static Account openedAccount(long id, Money openingBalance) {
		Account account = new Account(openingBalance);
		ReflectionTestUtils.setField(account, "id", id);
		return account;
	}

	private static void assertThatIsAValidationProblem(MvcTestResult result, String field) {
		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemType.VALIDATION_FAILED.urn());
		assertThat(result).bodyJson()
				.extractingPath("$.errors[*].field").asInstanceOf(LIST)
				.containsExactly(field);
		assertThat(result).bodyJson()
				.extractingPath("$.errors[0].message").asString().isNotBlank();
	}
}
