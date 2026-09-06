package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.common.ProblemType;
import hu.bankmonitor.testsupport.RowLockBarrier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static hu.bankmonitor.payments.common.Currency.EUR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/**
 * The two requests the whole idempotency mechanism exists for: one Idempotency Key,
 * arriving twice at the same moment rather than twice in a row.
 *
 * <p>Everything else about the mechanism has been asserted sequentially.
 * {@code WhatARepeatOfAKeyGetsBackTest} places each prior claim in the table by hand and
 * asks the port what a repeat gets; {@link RetryingATransferRequestMovesMoneyOnceTest}
 * sends the repeat through the endpoint once the first request has finished. Both would
 * stay green against a design that read the table before writing it — the check-then-act
 * those tests perform only after the act is over. This is where such a design fails, so it
 * is the only test in the suite the unique constraint is load-bearing for.
 *
 * <p>It is also the only place two branches of the shipped code can be reached at all: a
 * {@link hu.bankmonitor.payments.idempotency.RequestInProgressException} needs a claim held
 * while a second request asks about it, and the loser of a race to reclaim a {@code FAILED}
 * key needs a race. Ticket 17 left both untested rather than reaching them with a mock,
 * which would have asserted that an {@code if} was typed correctly and nothing about a
 * race.
 *
 * <h2>The three ways this test could pass while proving nothing</h2>
 *
 * <p><b>A transactional test method.</b> {@link TransferScenario} is not annotated
 * {@code @Transactional}, so the claim each request commits is one the other request's
 * connection can see. Wrapped in a single transaction, neither thread would observe the
 * other's claim, both would "succeed", and the race would be invisible rather than absent.
 * The price is emptying four tables by hand, which that class pays.
 *
 * <p><b>Threads that never overlap.</b> Two requests sent together are not two requests in
 * flight together: against an in-memory database the first can finish in less time than it
 * takes to dispatch the second, and the second then reads a settled world and replays a
 * stored {@code 201}. That failure mode is a correct service answering correctly — two
 * {@code 201}s and one Transfer — which is why it has to be excluded by construction rather
 * than hoped away. It was measured; see the table.
 *
 * <p><b>An assertion that says "at least".</b> Every claim below is a count. A design with
 * no mutual exclusion reserves twice and writes two Transfers, and every
 * {@code hasSizeGreaterThan} or {@code anySatisfy} written against it passes.
 *
 * <h2>Two races, lined up in two different places</h2>
 *
 * <p>Neither test starts its threads on a latch, which is a departure from the shape ticket
 * 18 sketched and the same one {@code ConcurrentReservationsHoldTheBalanceTest} made for the
 * reason recorded in {@code docs/design-decisions/13-reserve-funds.md}: a start latch lines
 * up two <em>beginnings</em> and nothing else. Measured here, adding one changes no outcome
 * in forty runs. What each test does instead is name the statement its two contenders have
 * to meet at, and {@link RowLockBarrier} holds them there.
 *
 * <p>The two races meet in different places, so the two armings differ:
 *
 * <ul>
 * <li><b>Two first attempts</b> are separated by the unique constraint, and the loser is
 * turned away without ever touching an Account. There is no second arrival to wait for, so
 * the winner is held at the first row lock it takes — which is inside the transaction that
 * closes its claim, and after the claim itself has committed — until the loser has been
 * answered. That is what holds the {@code IN_PROGRESS} window open across the loser's read.
 * <li><b>Two retries of a failed key</b> both get past the constraint the same way and meet
 * at the <em>read</em> of the claim. Held there, neither has reached the guarded update yet,
 * so both read {@code FAILED} and both go on to contend for it — which is the branch that
 * exists to decide between them.
 * </ul>
 *
 * <h2>What was measured</h2>
 *
 * <p>Twenty repeats of each test per row, against {@code 2ad2d80}.
 *
 * <table>
 *   <caption>Each defect, and how many runs of which test caught it</caption>
 *   <tr><th>Injected</th><th>First attempts</th><th>Retries</th></tr>
 *   <tr><td>the unique constraint dropped from {@code V3}</td><td>20/20</td><td>20/20</td></tr>
 *   <tr><td>{@code reclaimFailed}'s guard on {@code FAILED} dropped</td><td>—</td><td>20/20</td></tr>
 *   <tr><td><em>neither race lined up</em></td><td>3/20 <b>red</b></td><td>0/20</td></tr>
 *   <tr><td><em>a start latch added instead</em></td><td>0/20</td><td>0/20</td></tr>
 * </table>
 *
 * <p>The bottom two rows are the tests failing to hold rather than the code failing to
 * work, and they are why the barrier is here. Unlined-up, the first test reports
 * {@code [201, 201]} in three runs out of twenty — the overlap simply did not happen — and
 * the second stays green while quietly ceasing to reach the branch it is about: the dropped
 * reclaim guard is caught 20/20 with the threads lined up at the read, 8/20 lined up at the
 * row lock, and 0/20 with nothing lining them up at all.
 *
 * <p>Which of the two requests wins is the database's decision, and this test does not have
 * one: the answers are collected as a pair and asserted as a pair.
 */
@Import(RowLockBarrier.class)
class ConcurrentRequestsUnderOneKeyExecuteOnceTest extends TransferScenario {

	private static final long SOURCE = 1L;

	private static final long DESTINATION = 2L;

	/** More than twice what one Transfer below costs, so that executing twice is possible. */
	private static final long BALANCE = 1_000_00L;

	private static final long AMOUNT = 100_50L;

	private static final String KEY = "0d1f6c1e-6b0a-4a5f-9f1a-2c3d4e5f6a7b";

	private static final String TRANSFER = """
			{"fromAccountId": 1, "toAccountId": 2, "amountMinorUnits": 10050}""";

	/**
	 * Where the second race is lined up: the read of the claim, and deliberately not the
	 * insert that fails just before it. Held at the read, neither retry has reached the
	 * update that takes the key, so both still see the {@code FAILED} they contend over.
	 */
	private static final String THE_READ_OF_THE_CLAIM = "from idempotency_records";

	/**
	 * Deliberately longer than {@link RowLockBarrier}'s own timeout. A thread stuck at the
	 * barrier is then released by the barrier giving up, and fails on the assertion it was
	 * always going to fail on; with the two timeouts equal, which of them expired first
	 * would decide whether the report names the defect or only says an answer never came.
	 */
	private static final Duration BEFORE_GIVING_UP_ON_AN_ANSWER = Duration.ofSeconds(30);

	@Autowired
	private RowLockBarrier rowLocks;

	/**
	 * The guarantee, under the only conditions that can break it. One request creates the
	 * Transfer; the other is told the key is in progress and to come back, which is the
	 * advice that makes a client's retry policy safe rather than a second charge.
	 *
	 * <p>Three counts, and each rules out a different failure. Two {@code 201}s would mean
	 * the requests did not overlap, or that both were served from one claim. Two Transfer
	 * rows would mean the operation ran twice. A doubled Reserved Amount would mean it ran
	 * twice and left one of its Transfers somewhere this test does not look — the failure
	 * the row count on its own cannot see.
	 *
	 * <p>The {@code 409} is checked for the URN and the {@code Retry-After} that tell it
	 * apart from the other {@code 409} this endpoint sends. The two mean opposite things:
	 * this one is <em>wait</em>, and answering a lost race with the key-reused document
	 * would tell an honest client to give up on a Transfer that was about to succeed.
	 */
	@Test
	@DisplayName("two concurrent requests under one key: one 201, one 409, and one Transfer")
	void twoConcurrentRequestsUnderOneKeyCreateOneTransfer() throws Exception {
		openAccount(SOURCE, BALANCE, EUR);
		openAccount(DESTINATION, 0L, EUR);

		rowLocks.holdTheOneThreadThatReachesARowLock();
		List<Answer> answers = race(KEY, TRANSFER);

		assertThat(answers).extracting(Answer::status)
				.containsExactlyInAnyOrder(CREATED.value(), CONFLICT.value());
		refusalIn(answers).body().jsonPath("$.type")
				.isEqualTo(ProblemType.REQUEST_IN_PROGRESS.urn());
		assertThat(refusalIn(answers).retryAfter())
				.as("the half of the refusal a client acts on").isNotNull();

		assertThat(transferRows()).hasSize(1);
		assertThat(reservedAmountOf(SOURCE)).isEqualTo(AMOUNT);
		assertThat(claimedStatus(KEY)).isEqualTo("SUCCEEDED");
	}

	/**
	 * The recovery path racing itself. A {@code FAILED} key is retryable by design, and
	 * retryable is what makes "resend the same request" a safe policy — so two retries
	 * arriving at once (one client and its own timeout will do) must still move the money
	 * once.
	 *
	 * <p>Both retries are held at the read of the claim, so both find it {@code FAILED} and
	 * both go on to the conditional update that takes it. This is the only test that reaches
	 * the branch where that update matches nothing, and the loser is answered exactly as the
	 * loser of the first race is — by then the statement is true, because the winner is
	 * holding the claim it has just taken.
	 *
	 * <p>The source Account is funded <em>after</em> the failure and by enough for two
	 * Transfers, which is what makes a double execution visible here. Funded for one, a
	 * second execution would be refused by the overdraft check instead, and this would be a
	 * test that the Account has a balance rather than that the key was claimed once.
	 */
	@Test
	@DisplayName("two concurrent retries of a failed key execute exactly once")
	void twoConcurrentRetriesOfAFailedKeyExecuteOnce() throws Exception {
		openAccount(SOURCE, 0L, EUR);
		openAccount(DESTINATION, 0L, EUR);

		assertThat(answerTo(KEY, TRANSFER).status()).isEqualTo(UNPROCESSABLE_ENTITY.value());
		assertThat(claimedStatus(KEY)).as("the key the retries contend for").isEqualTo("FAILED");
		payIn(SOURCE, BALANCE);

		rowLocks.holdEachThreadAtItsFirstStatementNaming(THE_READ_OF_THE_CLAIM, 2);
		List<Answer> answers = race(KEY, TRANSFER);

		assertThat(answers).extracting(Answer::status)
				.containsExactlyInAnyOrder(CREATED.value(), CONFLICT.value());
		refusalIn(answers).body().jsonPath("$.type")
				.isEqualTo(ProblemType.REQUEST_IN_PROGRESS.urn());
		assertThat(refusalIn(answers).retryAfter())
				.as("a lost reclaim advises a retry exactly as a lost claim does").isNotNull();

		assertThat(transferRows()).hasSize(1);
		assertThat(reservedAmountOf(SOURCE)).isEqualTo(AMOUNT);
		assertThat(claimedStatus(KEY)).isEqualTo("SUCCEEDED");
	}

	/**
	 * Sends one request twice at once, on a barrier the caller has already armed, and reports
	 * what each attempt got back.
	 *
	 * <p>The answers are read one at a time rather than waited for as a pair, because the
	 * first race needs the barrier released between them: the request that takes the key is
	 * held inside its transaction and cannot answer until it is let go, so the first answer
	 * to arrive is necessarily the other one's. Releasing only once that answer is in hand is
	 * what guarantees the loser was refused while the claim was still open rather than after
	 * it closed. In the second race the two threads are released by each other's arrival, and
	 * the call in the middle only disarms.
	 *
	 * <p>Virtual threads, because these are two blocking round trips and the application
	 * under test serves them on virtual threads of its own.
	 */
	private List<Answer> race(String idempotencyKey, String body) throws Exception {
		ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
		try {
			CompletionService<Answer> answers = new ExecutorCompletionService<>(threads);
			answers.submit(() -> answerTo(idempotencyKey, body));
			answers.submit(() -> answerTo(idempotencyKey, body));

			Answer answeredWhileTheKeyWasHeld = nextAnswer(answers);
			rowLocks.release();
			return List.of(answeredWhileTheKeyWasHeld, nextAnswer(answers));
		}
		finally {
			rowLocks.release();
			threads.shutdownNow();
		}
	}

	/**
	 * Waiting with a timeout is what turns a thread that never returns from a suite that
	 * hangs into a test that fails.
	 */
	private static Answer nextAnswer(CompletionService<Answer> answers) throws Exception {
		Future<Answer> answered = answers.poll(
				BEFORE_GIVING_UP_ON_AN_ANSWER.toSeconds(), TimeUnit.SECONDS);
		assertThat(answered)
				.as("both requests answer within %s", BEFORE_GIVING_UP_ON_AN_ANSWER).isNotNull();
		return answered.get();
	}

	private Answer answerTo(String idempotencyKey, String body) {
		RestTestClient.BodyContentSpec answered = client().post().uri("/api/transfers")
				.header("X-Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.expectBody();
		EntityExchangeResult<byte[]> result = answered.returnResult();
		return new Answer(result.getStatus().value(),
				result.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER), answered);
	}

	/** Worth reaching for only after the counts above have said there is exactly one. */
	private static Answer refusalIn(List<Answer> answers) {
		return answers.stream().filter(answer -> answer.status() == CONFLICT.value())
				.findFirst().orElseThrow();
	}

	private void payIn(long accountId, long minorUnits) {
		database.update("UPDATE accounts SET balance_minor_units = ? WHERE id = ?",
				minorUnits, accountId);
	}

	/**
	 * What one request came back with.
	 *
	 * <p>The body is kept as the spec that asserts against it rather than as parsed content,
	 * because which of the two answers this is only becomes known once both are in — and a
	 * {@code 201} and a problem document have no type in common to be parsed into.
	 */
	private record Answer(int status, @Nullable String retryAfter, RestTestClient.BodyContentSpec body) {
	}
}
