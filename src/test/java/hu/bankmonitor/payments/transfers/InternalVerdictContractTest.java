package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.payments.SecurityConfiguration;
import hu.bankmonitor.payments.common.ProblemType;
import hu.bankmonitor.payments.transfers.checks.Check;
import hu.bankmonitor.payments.transfers.checks.CheckNotRequiredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.io.UnsupportedEncodingException;

import static hu.bankmonitor.payments.transfers.checks.Check.FRAUD;
import static hu.bankmonitor.payments.transfers.checks.Verdict.APPROVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/**
 * What a Check service posts to report a Verdict, what it gets back, and what happens to a
 * caller that cannot prove it is one.
 *
 * <p>No database: {@link VerdictRecording} is stubbed, so every assertion here is about the
 * wire. That the same call really settles a Transfer is
 * {@link ReportedVerdictsSettleTheTransferOverHttpTest}'s claim.
 *
 * <p><b>The filters are left on</b>, unlike {@link TransferRequestContractTest}, and that is
 * the whole reason this class imports {@link SecurityConfiguration}: the shared secret is
 * enforced by the filter chain rather than by the controller, so a slice test that disabled
 * the filters would assert the mapping and silently drop the one authorization rule this
 * application has.
 */
@WebMvcTest(InternalVerdictController.class)
@Import(SecurityConfiguration.class)
class InternalVerdictContractTest {

	private static final long TRANSFER = 31L;

	/**
	 * The header and the path below are written out here rather than read from the production
	 * constants, on {@code TransferController}'s reasoning: a test reading the same constant
	 * as the thing under test follows a rename silently instead of failing on it. Both are
	 * part of the contract a Check service is configured against.
	 */
	private static final String SECRET_HEADER = "X-Internal-Secret";

	private static final String APPROVAL = """
			{"verdict": "APPROVED"}""";

	@Autowired
	private MockMvcTester mvc;

	@MockitoBean
	private VerdictRecording verdicts;

	@Value("${payments.internal.shared-secret}")
	private String secret;

	@Test
	@DisplayName("a Verdict reported with the secret is recorded and answered with where the Transfer got to")
	void recordsTheVerdictAndAnswersWithTheTransfersNewStatus() {
		given(verdicts.recordVerdict(TRANSFER, FRAUD, APPROVED)).willReturn(TransferStatus.SETTLED);

		MvcTestResult result = report(TRANSFER, FRAUD, APPROVAL);

		assertThat(result).hasStatus(HttpStatus.OK);
		assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
		assertThat(result).bodyJson().extractingPath("$.transferId").isEqualTo(31);
		assertThat(result).bodyJson().extractingPath("$.check").isEqualTo("FRAUD");
		assertThat(result).bodyJson().extractingPath("$.verdict").isEqualTo("APPROVED");
		assertThat(result).bodyJson().extractingPath("$.transferStatus").isEqualTo("SETTLED");
	}

	/**
	 * The Transfer is still {@code PENDING} while another Check is outstanding, and the
	 * caller is told so rather than being answered as though nothing had happened. It is the
	 * same {@code 200}: the report landed either way, and where the Transfer got to is the
	 * news.
	 */
	@Test
	@DisplayName("a Verdict that leaves other Checks outstanding answers PENDING")
	void answersPendingWhileAnotherCheckIsOutstanding() {
		given(verdicts.recordVerdict(TRANSFER, FRAUD, APPROVED)).willReturn(TransferStatus.PENDING);

		assertThat(report(TRANSFER, FRAUD, APPROVAL)).bodyJson()
				.extractingPath("$.transferStatus").isEqualTo("PENDING");
	}

	@Nested
	@DisplayName("the shared secret")
	class SharedSecret {

		/**
		 * Refused <em>before</em> the domain operation, which is the whole of what the rule
		 * buys: a Verdict that reached {@link VerdictRecording} would have settled the
		 * Transfer whatever status the caller was answered with afterwards.
		 */
		@Test
		@DisplayName("a request with no secret never reaches the domain operation")
		void refusesARequestWithNoSecretBeforeRecordingAnything() {
			MvcTestResult result = mvc.post().uri(path(TRANSFER, FRAUD))
					.contentType(MediaType.APPLICATION_JSON)
					.content(APPROVAL)
					.exchange();

			assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
			assertThatIsAProblemDocument(result, ProblemType.FORBIDDEN);
			then(verdicts).should(never()).recordVerdict(anyLong(), any(), any());
		}

		@Test
		@DisplayName("a request with the wrong secret is refused the same way")
		void refusesARequestWithTheWrongSecret() {
			MvcTestResult result = mvc.post().uri(path(TRANSFER, FRAUD))
					.header(SECRET_HEADER, "not-the-secret")
					.contentType(MediaType.APPLICATION_JSON)
					.content(APPROVAL)
					.exchange();

			assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
			assertThatIsAProblemDocument(result, ProblemType.FORBIDDEN);
			then(verdicts).should(never()).recordVerdict(anyLong(), any(), any());
		}

		/**
		 * A wrong secret and a missing one are answered identically, down to the wording. A
		 * refusal that said which of the two had happened would tell a caller probing the
		 * endpoint whether the header it guessed at is the one being checked.
		 */
		@Test
		@DisplayName("a wrong secret and a missing one are indistinguishable")
		void answersAWrongSecretExactlyAsItAnswersAMissingOne() throws UnsupportedEncodingException {
			MvcTestResult missing = mvc.post().uri(path(TRANSFER, FRAUD))
					.contentType(MediaType.APPLICATION_JSON).content(APPROVAL).exchange();
			MvcTestResult wrong = mvc.post().uri(path(TRANSFER, FRAUD))
					.header(SECRET_HEADER, "not-the-secret")
					.contentType(MediaType.APPLICATION_JSON).content(APPROVAL).exchange();

			assertThat(wrong.getResponse().getContentAsString())
					.isEqualTo(missing.getResponse().getContentAsString());
		}

		/**
		 * The secret guards the prefix rather than the one handler under it, so a path
		 * {@code /internal} has no endpoint at is refused before the dispatcher can say so.
		 * Without that the rule would have to be repeated on every internal endpoint added
		 * later, and the one that forgot would be open.
		 */
		@Test
		@DisplayName("the whole internal prefix is behind the secret, not just this endpoint")
		void guardsThePrefixRatherThanTheHandler() {
			assertThat(mvc.get().uri("/internal/not-an-endpoint").exchange())
					.hasStatus(HttpStatus.FORBIDDEN);
		}
	}

	@Nested
	@DisplayName("refusals from the domain operation")
	class Refusals {

		@Test
		@DisplayName("a Verdict for a Transfer that does not exist is a 404")
		void answersAnUnknownTransferWithNotFound() {
			willThrow(new UnknownTransferException(TRANSFER))
					.given(verdicts).recordVerdict(TRANSFER, FRAUD, APPROVED);

			MvcTestResult result = report(TRANSFER, FRAUD, APPROVAL);

			assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
			assertThatIsAProblemDocument(result, ProblemType.NOT_FOUND);
			assertThat(result).bodyJson().extractingPath("$.transferId").isEqualTo(31);
		}

		/**
		 * The pair is what the refusal is about — this Transfer does not require this Check —
		 * so both halves are on the wire. A URN of its own rather than the {@code 404} above's,
		 * because the URN is the only member a client may branch on: sharing one would leave
		 * "no such Transfer" and "not a Check of this Transfer" told apart by which properties
		 * happened to be present.
		 */
		@Test
		@DisplayName("a Verdict naming a Check the Transfer does not require is a 404 of its own")
		void answersACheckTheTransferDoesNotRequireWithItsOwnProblemType() {
			willThrow(new CheckNotRequiredException(TRANSFER, FRAUD))
					.given(verdicts).recordVerdict(TRANSFER, FRAUD, APPROVED);

			MvcTestResult result = report(TRANSFER, FRAUD, APPROVAL);

			assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
			assertThatIsAProblemDocument(result, ProblemType.CHECK_NOT_REQUIRED);
			assertThat(result).bodyJson().extractingPath("$.transferId").isEqualTo(31);
			assertThat(result).bodyJson().extractingPath("$.check").isEqualTo("FRAUD");
		}

		/**
		 * {@code 409}, and it carries the status the Transfer had already reached. A Check
		 * service redelivering the Verdict that settled a Transfer and one answering a Transfer
		 * the reaper expired meet the same refusal, and the status is the only thing that tells
		 * them what became of their report.
		 */
		@Test
		@DisplayName("a Verdict on a finished Transfer is a 409 naming the status it found")
		void answersALateVerdictWithConflictAndTheStatusItFound() {
			willThrow(new TransferNotPendingException(TRANSFER, TransferStatus.SETTLED))
					.given(verdicts).recordVerdict(TRANSFER, FRAUD, APPROVED);

			MvcTestResult result = report(TRANSFER, FRAUD, APPROVAL);

			assertThat(result).hasStatus(HttpStatus.CONFLICT);
			assertThatIsAProblemDocument(result, ProblemType.TRANSFER_NOT_PENDING);
			assertThat(result).bodyJson().extractingPath("$.transferId").isEqualTo(31);
			assertThat(result).bodyJson().extractingPath("$.transferStatus").isEqualTo("SETTLED");
		}

		/**
		 * No {@code Retry-After}, which is the machine-readable half of the refusal on
		 * {@code TransferController}'s precedent: a Transfer never leaves a terminal status, so
		 * a caller that retried this would retry for ever.
		 */
		@Test
		@DisplayName("a late Verdict does not invite a retry")
		void offersNoRetryAfterOnALateVerdict() {
			willThrow(new TransferNotPendingException(TRANSFER, TransferStatus.EXPIRED))
					.given(verdicts).recordVerdict(TRANSFER, FRAUD, APPROVED);

			assertThat(report(TRANSFER, FRAUD, APPROVAL)).doesNotContainHeader("Retry-After");
		}
	}

	@Nested
	@DisplayName("what the request has to say")
	class RequestContract {

		@ParameterizedTest
		@ValueSource(strings = {"{}", """
				{"verdict": null}"""})
		@DisplayName("a report that names no Verdict is rejected against the member that carries it")
		void rejectsAReportWithNoVerdict(String body) {
			MvcTestResult result = report(TRANSFER, FRAUD, body);

			assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
			assertThatIsAProblemDocument(result, ProblemType.VALIDATION_FAILED);
			assertThat(result).bodyJson().extractingPath("$.errors[*].field").asInstanceOf(LIST)
					.containsExactly("verdict");
			then(verdicts).should(never()).recordVerdict(anyLong(), any(), any());
		}

		@Test
		@DisplayName("a Verdict this domain has no name for lists the ones it has")
		void rejectsAVerdictOutsideTheEnumeration() {
			MvcTestResult result = report(TRANSFER, FRAUD, """
					{"verdict": "MAYBE"}""");

			assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
			assertThatIsAProblemDocument(result, ProblemType.VALIDATION_FAILED);
			assertThat(result).bodyJson().extractingPath("$.errors[0].message")
					.isEqualTo("must be one of: APPROVED, REJECTED");
		}

		/**
		 * The Check is a path segment, so a name outside {@link Check} fails the conversion
		 * before the handler runs — and is reported against {@code check} in the same shape a
		 * rejected body member gets, which is what {@code ProblemDocumentAdvice} exists to make
		 * true of every rejected value wherever it arrived.
		 */
		@Test
		@DisplayName("a Check this domain has no name for is rejected against the path segment")
		void rejectsACheckOutsideTheEnumeration() {
			MvcTestResult result = mvc.post().uri("/internal/transfers/31/checks/ASTROLOGY")
					.header(SECRET_HEADER, secret)
					.contentType(MediaType.APPLICATION_JSON)
					.content(APPROVAL)
					.exchange();

			assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
			assertThatIsAProblemDocument(result, ProblemType.VALIDATION_FAILED);
			assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("check");
			assertThat(result).bodyJson().extractingPath("$.errors[0].message")
					.isEqualTo("must be one of: FRAUD, MANUAL_APPROVAL");
		}
	}

	private MvcTestResult report(long transferId, Check check, String body) {
		return mvc.post().uri(path(transferId, check))
				.header(SECRET_HEADER, secret)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)
				.exchange();
	}

	private static String path(long transferId, Check check) {
		return "/internal/transfers/%d/checks/%s".formatted(transferId, check);
	}

	private static void assertThatIsAProblemDocument(MvcTestResult result, ProblemType type) {
		assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(type.urn());
		assertThat(result).bodyJson().extractingPath("$.title").isNotNull();
		assertThat(result).bodyJson().extractingPath("$.status").isNotNull();
		assertThat(result).bodyJson().extractingPath("$.detail").isNotNull();
		assertThat(result).bodyJson().extractingPath("$.instance").isNotNull();
	}
}
