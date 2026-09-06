package hu.bankmonitor.payments.transfers;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import hu.bankmonitor.testsupport.boundaryviolations.secondwriter.SecondTransferWriter;
import hu.bankmonitor.testsupport.boundaryviolations.secondwriter.TransferStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What makes "a Transfer without its Check Ledger is impossible" a structural claim rather
 * than a description of the one path that exists today.
 *
 * <p>{@code EveryTransferOpensItsCheckLedgerTest} shows that {@link FundsReservation}
 * writes both. That is a claim about a path, and a second path would not make it false —
 * a settlement retry, a fixture, a back-office correction tool, anything that saved a
 * {@code Transfer} of its own would produce the {@code PENDING} row with an empty ledger
 * that the whole design is arranged to prevent, and every existing test would stay green.
 *
 * <p>So the rule is about the write rather than about the ledger: a Transfer comes into
 * being in exactly one place, and that place opens the ledger unconditionally in the same
 * transaction. The compiler already stops another <em>slice</em> — {@link
 * TransferRepository} is package-private per design decision 30 — and this covers the
 * inside of {@code transfers}, which is where the second writer would actually be added.
 *
 * <p><b>What it does not claim.</b> Not that {@code FundsReservation} opens the ledger; that
 * is the booted test's, and this rule would stay green if the call were deleted. The two
 * are halves of one argument and neither is worth much alone.
 */
class NothingButTheReservationCreatesATransferTest {

	/**
	 * By dependency rather than by call, because {@link TransferRepository} has exactly one
	 * write method and a class that holds the repository at all is one edit away from
	 * calling it.
	 *
	 * <p>That strictness is what {@link TransferQueries} exists to pay for. Ticket 15 gave
	 * the slice a read side, and rather than exempt it here — which would have conceded the
	 * "one edit away" margin, and again for each later reader — the reads went onto an
	 * interface of their own that does not declare {@code save}. So this rule still names
	 * one class and still forbids the dependency outright, and a reader cannot write a
	 * Transfer because it cannot name the method that would.
	 */
	private static final ArchRule ONLY_THE_RESERVATION_WRITES_A_TRANSFER = noClasses()
			.that().doNotHaveFullyQualifiedName(FundsReservation.class.getName())
			.should().dependOnClassesThat().haveFullyQualifiedName(TransferRepository.class.getName())
			.because("a Transfer written anywhere else would be PENDING against an empty check ledger");

	@Test
	@DisplayName("a Transfer comes into being in exactly one place")
	void onlyTheReservationWritesATransfer() {
		ONLY_THE_RESERVATION_WRITES_A_TRANSFER.check(new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("hu.bankmonitor.payments"));
	}

	/**
	 * The rule against code that breaks it, on {@code ModuleBoundariesHoldTest}'s reasoning:
	 * a prohibition that has quietly stopped matching anything passes just as greenly as one
	 * that holds.
	 */
	@Test
	@DisplayName("a second class holding the repository is reported, by name")
	void detectsASecondWriter() {
		JavaClasses secondWriter = new ClassFileImporter()
				.importPackages(SecondTransferWriter.class.getPackageName());

		assertThatThrownBy(() -> secondWriterRule().check(secondWriter))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("SecondTransferWriter");
	}

	/**
	 * The same rule aimed at the fixture's own repository. It has to be restated rather than
	 * reused, because {@link #ONLY_THE_RESERVATION_WRITES_A_TRANSFER} names two production
	 * classes the fixture package does not contain — which is exactly why the fixture is a
	 * separate package rather than a class beside the real ones.
	 *
	 * <p>{@code "FundsReservation"} therefore stays a string: it is the production name this
	 * rule deliberately expects to match nothing here. {@link TransferStore} is a class the
	 * fixture does contain, so naming it is the compiler's job — renaming it would otherwise
	 * leave a rule that matches nothing and passes.
	 */
	private static ArchRule secondWriterRule() {
		return noClasses()
				.that().doNotHaveSimpleName("FundsReservation")
				.should().dependOnClassesThat().haveSimpleName(TransferStore.class.getSimpleName());
	}
}
