package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.common.Money;
import hu.bankmonitor.payments.common.ProblemType;
import hu.bankmonitor.payments.transfers.checks.Check;
import hu.bankmonitor.payments.transfers.checks.CheckLedger;
import hu.bankmonitor.payments.transfers.checks.Verdict;
import hu.bankmonitor.testsupport.BootedApplicationTest;
import hu.bankmonitor.testsupport.TransferRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static hu.bankmonitor.testsupport.TransferRows.DESTINATION_ACCOUNT;
import static hu.bankmonitor.testsupport.TransferRows.SOURCE_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code GET /api/transfers/{id}}: the resource a Transfer gets of its own, which is what
 * lets a pending Transfer's state live in a URL and survive a refresh (ticket 41), and the
 * one place its Check Ledger reaches the wire.
 *
 * <p>The ledger is what turns "why is this stuck" from a support question into a field on a
 * screen, so most of what follows is about a claim the amounts cannot make: that an
 * outstanding Check is told apart from an answered one by a reader who was not told which
 * Checks to expect.
 *
 * <p>Rows go in as SQL for {@link TransferListingTest}'s reason, and the ledger rows go in
 * the same way for a sharper one. {@code REJECTED} beside the Check that rejected it, and
 * {@code SETTLED} beside two approvals, are the pairings this endpoint reports; driving them
 * through {@code VerdictRecording} would make every fixture here a second test of ticket 20
 * before this one's claim could be stated.
 */
class OneTransferByIdTest extends BootedApplicationTest {

	private static final String TRANSFERS = "/api/transfers";

	private static final long TRANSFER = 31L;

	private static final Instant REQUESTED_AT = Instant.parse("2026-09-06T09:41:00Z");

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private CheckLedger ledger;

	@BeforeEach
	void startFromTwoAccountsAndNoTransfers() {
		TransferRows.startFromTwoAccountsAndNoTransfers(jdbc);
	}

	@AfterEach
	void emptyTheLedgerTransferAndAccountTables() {
		TransferRows.empty(jdbc);
	}

	/**
	 * Every member the listing sends, sent here too. One shape across both endpoints is what
	 * lets ticket 32 generate a single Transfer type, and what makes the page a client lands on
	 * after submitting the same page it refreshes an hour later. What this endpoint adds to it
	 * is the {@code checks} member below, which the listing leaves out.
	 *
	 * <p>A settled cross-Currency Transfer, so that the rate is read back off a Transfer that
	 * has already moved money: design decision 15 locks the rate on at request time precisely
	 * so a settled conversion can be audited, and a rate that only survived while the Transfer
	 * was {@code PENDING} would not be worth locking. {@code 120.00 EUR} at {@code 390} is
	 * {@code 46 800 HUF} — the figure a reader can check by hand, and one that would not look
	 * right if a division by a hundred had gone missing between the column and the wire.
	 *
	 * <p>The moment the rate was quoted is asserted against the Transfer's own timestamp rather
	 * than against a literal, and it is the <em>later</em> of the two. That is what the
	 * application produces and it is worth stating, because the intuitive reading — the quote
	 * comes first, then the Transfer it prices — is wrong about which instant {@code createdAt}
	 * holds. It is stamped when the request arrives, before the key is even claimed; the quote
	 * comes back some way into phase two. Two timestamps about the beginning of one Transfer,
	 * in the order the request actually goes through.
	 */
	@Test
	@DisplayName("a Transfer is fetched by its identifier, in the shape the listing sends")
	void fetchesOneTransferByItsIdentifier() {
		insertSettledTransferApprovedByBothChecks();

		client().get().uri(TRANSFERS + "/" + TRANSFER)
				.exchange()
				.expectStatus().isOk()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
				.expectBody()
				.jsonPath("$.id").isEqualTo(TRANSFER)
				.jsonPath("$.fromAccountId").isEqualTo(SOURCE_ACCOUNT)
				.jsonPath("$.toAccountId").isEqualTo(DESTINATION_ACCOUNT)
				.jsonPath("$.status").isEqualTo("SETTLED")
				.jsonPath("$.debitedAmountMinorUnits").isEqualTo(120_00L)
				.jsonPath("$.debitedAmountCurrency").isEqualTo("EUR")
				.jsonPath("$.creditedAmountMinorUnits").isEqualTo(46_800L)
				.jsonPath("$.creditedAmountCurrency").isEqualTo("HUF")
				.jsonPath("$.exchangeRate").value((Number rate) ->
						assertThat(new BigDecimal(rate.toString())).isEqualByComparingTo("390"))
				.jsonPath("$.exchangeRateFetchedAt").value((String quotedAt) ->
						assertThat(Instant.parse(quotedAt)).isAfter(REQUESTED_AT))
				.jsonPath("$.createdAt").isEqualTo(REQUESTED_AT.toString());
	}

	/**
	 * The other half of the pair, and the reason neither member is required in the schema: a
	 * Transfer between two Accounts in one Currency was never quoted, so there is no rate to
	 * report. A {@code 1} here would describe a call to the provider that this application
	 * deliberately never made.
	 *
	 * <p><b>Asserted against the raw body rather than with {@code jsonPath().doesNotExist()}</b>,
	 * which cannot tell an absent member from a present null one and so passes either way. That
	 * is the whole difference {@code @JsonInclude(NON_NULL)} on {@link TransferResponse} makes,
	 * and the difference between the published schema being true and being aspirational, so the
	 * assertion has to be able to see it. One substring covers both members: the longer name
	 * begins with the shorter.
	 */
	@Test
	@DisplayName("a same-Currency Transfer comes back with no rate at all")
	void reportsNoRateForATransferThatNeededNone() {
		insertTransfer(TransferStatus.SETTLED);

		client().get().uri(TRANSFERS + "/" + TRANSFER)
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.value(body -> assertThat(body).doesNotContain("exchangeRate"));
	}

	/**
	 * Every Check the Transfer required, each carrying the answer it gave. A settled Transfer
	 * is the case where the ledger explains a status the Transfer has already reached, which is
	 * what makes this response an audit trail as well as a pending-state screen.
	 */
	@Test
	@DisplayName("the response carries every Check the Transfer requires, with its Verdict")
	void reportsEveryCheckTheTransferRequiresWithItsVerdict() {
		insertSettledTransferApprovedByBothChecks();

		client().get().uri(TRANSFERS + "/" + TRANSFER)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.checks[*].check")
				.value((List<String> checks) -> assertThat(checks).containsExactly("FRAUD", "MANUAL_APPROVAL"))
				.jsonPath("$.checks[*].verdict")
				.value((List<String> verdicts) -> assertThat(verdicts).containsExactly("APPROVED", "APPROVED"));
	}

	/**
	 * <b>The claim the ticket exists for.</b> A {@code PENDING} Transfer is stuck on something,
	 * and this is where a reader finds out on which Check: the answered one reports its
	 * approval, and the outstanding one carries no answer at all.
	 *
	 * <p>The member is asserted absent rather than null, because the two are different promises
	 * to a generated client, and absence is the one this API can keep. {@code CheckLedgerEntry}
	 * makes the same claim about the column it reads from — an unanswered Check has no Verdict,
	 * rather than a third Verdict standing for nobody having given one.
	 */
	@Test
	@DisplayName("an outstanding Check carries no Verdict, where an answered one carries its own")
	void tellsAnOutstandingCheckApartFromAnAnsweredOne() {
		insertTransfer(TransferStatus.PENDING);
		insertCheck(Check.FRAUD, Verdict.APPROVED);
		insertCheck(Check.MANUAL_APPROVAL, null);

		client().get().uri(TRANSFERS + "/" + TRANSFER)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.status").isEqualTo("PENDING")
				.jsonPath("$.checks[0].check").isEqualTo("FRAUD")
				.jsonPath("$.checks[0].verdict").isEqualTo("APPROVED")
				.jsonPath("$.checks[1].check").isEqualTo("MANUAL_APPROVAL")
				.jsonPath("$.checks[1].verdict").doesNotExist();
	}

	/**
	 * Which Check said no, which is what ticket 41's rejected Transfer renders. The remaining
	 * Check is still outstanding and stays that way: a rejection ends a Transfer without
	 * waiting for the rest of the ledger to answer, so the ledger of a rejected Transfer is
	 * normally incomplete and the screen has to be able to say so.
	 */
	@Test
	@DisplayName("a rejected Transfer reports which Check rejected it")
	void namesTheCheckThatRejectedTheTransfer() {
		insertTransfer(TransferStatus.REJECTED);
		insertCheck(Check.FRAUD, Verdict.REJECTED);
		insertCheck(Check.MANUAL_APPROVAL, null);

		client().get().uri(TRANSFERS + "/" + TRANSFER)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.status").isEqualTo("REJECTED")
				.jsonPath("$.checks[0].check").isEqualTo("FRAUD")
				.jsonPath("$.checks[0].verdict").isEqualTo("REJECTED")
				.jsonPath("$.checks[1].verdict").doesNotExist();
	}

	/**
	 * The rows go in here in the reverse of the order they come back in, so what is asserted is
	 * the query's order rather than the order the ledger happened to be opened in.
	 *
	 * <p>It is part of the contract rather than a detail of the query. A Check Ledger that
	 * reshuffles itself between two refetches of unchanged data is a screen an operator cannot
	 * read, whichever way ticket 41 lays it out. Ticket 15 fixed the listing's order for the
	 * same reason and needed a tie break to make it total; here the Check itself is the key and
	 * a Transfer cannot hold two rows for one Check, so there is no tie to break.
	 *
	 * <p>The order is the Check's stored name, so it is alphabetical rather than the policy's:
	 * what is promised is that it is stable and does not depend on the order the rows were
	 * written in, which is the whole of what a reader needs.
	 */
	@Test
	@DisplayName("the Checks come back in one order, whichever order their rows were written in")
	void reportsTheChecksInAnOrderTheRowsDoNotChoose() {
		insertTransfer(TransferStatus.PENDING);
		insertCheck(Check.MANUAL_APPROVAL, null);
		insertCheck(Check.FRAUD, null);

		client().get().uri(TRANSFERS + "/" + TRANSFER)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.checks[*].check")
				.value((List<String> checks) -> assertThat(checks).containsExactly("FRAUD", "MANUAL_APPROVAL"));
	}

	/**
	 * A Transfer with no ledger rows is reported as requiring no Checks, rather than refused.
	 *
	 * <p>{@code LedgerDecision.decide} refuses exactly this ledger, because it is about to move
	 * money on what the rows say and no rows would mean moving it unchecked. Reporting decides
	 * nothing, and refusing here would make the one screen that could show an operator a
	 * Transfer nothing will ever settle the one screen that will not open.
	 *
	 * <p>The rows are written by hand precisely because the application cannot produce this
	 * state: a Transfer comes into being with its ledger in one transaction, and an ArchUnit
	 * rule holds that to one place. It is reachable by a failed migration or a hand-edited
	 * database, which is when somebody most needs the screen.
	 */
	@Test
	@DisplayName("a Transfer with no ledger requires no Checks, rather than being unreadable")
	void reportsATransferWithNoLedgerAsRequiringNoChecks() {
		insertTransfer(TransferStatus.PENDING);

		client().get().uri(TRANSFERS + "/" + TRANSFER)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.status").isEqualTo("PENDING")
				.jsonPath("$.checks").isArray()
				.jsonPath("$.checks.length()").isEqualTo(0);
	}

	/**
	 * The Transfer and its ledger are read in one transaction, asserted directly rather than
	 * inferred from the one caller happening to be transactional.
	 *
	 * <p>What it buys is that the two halves of this response cannot disagree. A Verdict
	 * committing between a Transfer read and a ledger read would produce a body reporting a
	 * {@code SETTLED} Transfer that is still waiting on a Check — a screen saying both that the
	 * money moved and that it has not, in the one place an operator goes to find out which.
	 */
	@Test
	@DisplayName("a Transfer's ledger cannot be read outside a transaction")
	void refusesToReadALedgerWithNoTransaction() {
		assertThatThrownBy(() -> ledger.stateOf(TRANSFER))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	/**
	 * {@code 404} here is the meaning ticket 05 settled for it — <em>the path names
	 * nothing</em> — rather than a second meaning for the status. Ticket 14 answers an unknown
	 * Account ID inside a Transfer's payload with {@code 422} precisely so that this one stays
	 * unambiguous: there, {@code /api/transfers} existed and the body was unprocessable; here,
	 * the identifier <em>is</em> the path.
	 */
	@Test
	@DisplayName("an identifier no Transfer has is a not-found problem document")
	void refusesAnIdentifierNoTransferHas() {
		client().get().uri(TRANSFERS + "/4242")
				.exchange()
				.expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.type").isEqualTo(ProblemType.NOT_FOUND.urn())
				.jsonPath("$.title").isNotEmpty()
				.jsonPath("$.detail").isNotEmpty()
				.jsonPath("$.status").isEqualTo(404)
				.jsonPath("$.instance").isEqualTo(TRANSFERS + "/4242")
				.jsonPath("$.transferId").isEqualTo(4242);
	}

	/**
	 * An identifier of the wrong <em>kind</em> is a rejected value rather than a missing
	 * resource, and it is reported against {@code id} in the same shape a rejected body field
	 * gets. Answering {@code 404} instead would be defensible and would cost the caller the
	 * one thing this document tells them: that no amount of retrying that URL will help.
	 */
	@Test
	@DisplayName("an identifier that is not a number is a rejected value, named as one")
	void refusesAnIdentifierThatIsNotANumber() {
		client().get().uri(TRANSFERS + "/not-a-number")
				.exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.type").isEqualTo(ProblemType.VALIDATION_FAILED.urn())
				.jsonPath("$.errors[*].field")
				.value((List<String> fields) -> assertThat(fields).containsExactly("id"))
				.jsonPath("$.errors[0].message").isEqualTo("must be a whole number");
	}

	/**
	 * The Transfer whose two amounts are denominated differently, which is the only kind of row
	 * on which a shape that had collapsed the two Currencies into one would be caught.
	 */
	private void insertSettledTransferApprovedByBothChecks() {
		insertTransfer(TransferStatus.SETTLED,
				new Money(120_00L, Currency.EUR), new Money(46_800L, Currency.HUF));
		insertCheck(Check.FRAUD, Verdict.APPROVED);
		insertCheck(Check.MANUAL_APPROVAL, Verdict.APPROVED);
	}

	/**
	 * The amount is the same on both sides for every Transfer whose ledger is the claim, which
	 * also makes this the Transfer that was never quoted.
	 */
	private void insertTransfer(TransferStatus status) {
		Money sameOnBothSides = new Money(100_00L, Currency.EUR);
		insertTransfer(status, sameOnBothSides, sameOnBothSides);
	}

	/** Every Transfer this class needs was requested at the same instant. */
	private void insertTransfer(TransferStatus status, Money debitedAmount, Money creditedAmount) {
		TransferRows.insertTransfer(jdbc, TRANSFER, status, REQUESTED_AT, debitedAmount, creditedAmount);
	}

	private void insertCheck(Check check, @Nullable Verdict verdict) {
		TransferRows.insertCheck(jdbc, TRANSFER, check, verdict);
	}
}
