package hu.bankmonitor.payments.mockfx;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The isolation that makes the stand-in provider a third party rather than this
 * application wearing a costume, at the level the compiler cannot see.
 *
 * <p>Package-private access already stops most of this, and design decision 30's cycle
 * rule would catch a loop — but neither catches the thing worth catching here, which is
 * <em>any</em> reach into the domain at all, cycle or not. Sharing {@code Currency} with
 * the provider would compile, would introduce no cycle, and would quietly make the
 * provider's wire format track ours.
 *
 * <p>Unlike the rules in {@code ModuleBoundariesHoldTest}, this one needs no fixture that
 * breaks it: it is written over classes that exist, so it cannot pass by matching
 * nothing. The first test here is what says so.
 */
class MockProviderDependsOnNothingOfOursTest {

	private static final String ROOT_PACKAGE = "hu.bankmonitor.payments";

	private static final String PROVIDER_PACKAGE = ROOT_PACKAGE + ".mockfx";

	private static final DescribedPredicate<JavaClass> ANYTHING_ELSE_OF_OURS =
			resideInAPackage(ROOT_PACKAGE + "..")
					.and(resideOutsideOfPackage(PROVIDER_PACKAGE + ".."))
					.as("this application's own code, outside the provider");

	private static final ArchRule PROVIDER_REACHES_INTO_NOTHING_OF_OURS = noClasses()
			.that().resideInAPackage(PROVIDER_PACKAGE + "..")
			.should().dependOnClassesThat(ANYTHING_ELSE_OF_OURS)
			.because("a third party does not share our types, and one that did would track our changes");

	private final JavaClasses productionClasses = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages(ROOT_PACKAGE);

	/** Without this the rule below would hold over an empty set, which every rule does. */
	@Test
	@DisplayName("the provider has classes for the rule to be about")
	void hasClassesForTheRuleToBeAbout() {
		assertThat(productionClasses.stream().filter(type -> type.getPackageName().startsWith(PROVIDER_PACKAGE)))
				.isNotEmpty();
	}

	@Test
	@DisplayName("nothing in the provider reaches into this application")
	void reachesIntoNothingOfOurs() {
		PROVIDER_REACHES_INTO_NOTHING_OF_OURS.check(productionClasses);
	}
}
