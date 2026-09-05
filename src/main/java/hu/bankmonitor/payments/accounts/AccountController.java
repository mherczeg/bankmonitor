package hu.bankmonitor.payments.accounts;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The Accounts resource, under the {@code /api} prefix the security chain opens by name.
 *
 * <p>It holds no repository — the boundary {@code ModuleBoundariesHoldTest} enforces — and
 * no rule of its own: what a request may say is declared as constraints on
 * {@link CreateAccountRequest}, so a refusal is the one problem document of design
 * decision 18 with an entry naming the field, rather than a check written by hand here.
 *
 * <p>The mapping from {@link Account} to {@link AccountResponse} is applied here rather
 * than in {@link AccountService} — the factory itself sits on the response type, but this
 * is the layer that calls it. The wire shape is the web layer's concern, and a service that
 * returned it would have the API's field names reaching into the transaction boundary.
 *
 * <p>The path is written out here and again in the tests that call it, rather than shared
 * through a constant. A renamed path is a breaking change for the generated frontend types
 * of ticket 32, and a test reading the same constant as the mapping would follow the rename
 * silently instead of failing on it.
 */
@RestController
@RequestMapping("/api/accounts")
class AccountController {

	private final AccountService accounts;

	AccountController(AccountService accounts) {
		this.accounts = accounts;
	}

	/**
	 * Every Account, with its balance and its Available Balance, so an operator can both
	 * see what an Account holds and tell how much of it is already committed.
	 *
	 * <p>A bare array rather than an object wrapping one. An envelope earns its place when
	 * there is something to put beside the items — a cursor, a total, a page number — and
	 * design decision 31 declines pagination, so today it would be a wrapper around
	 * nothing. Adding one later is a breaking change either way, and the frontend types of
	 * ticket 32 turn it into a compile error rather than a silent one.
	 */
	@GetMapping
	List<AccountResponse> listAccounts() {
		return accounts.listAll().stream().map(AccountResponse::of).toList();
	}

	/**
	 * Answers {@code 201} with the created Account, and deliberately without a
	 * {@code Location} header: this API has no single-Account resource for one to point
	 * at, and a header addressing a {@code 404} would be worse than its absence. The body
	 * carries the identifier, which is what a caller needs. See
	 * {@code docs/deferred.md}.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	AccountResponse openAccount(@Valid @RequestBody CreateAccountRequest request) {
		return AccountResponse.of(accounts.open(request.openingBalance()));
	}
}
