package hu.bankmonitor.payments;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Controller;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The module boundaries of design decision 30, as a test rather than a diagram.
 *
 * <p>Most of those boundaries need no test: Java's default access level is
 * package-private and the compiler enforces it, so a package-private repository is
 * uncallable from another slice and the build says so. What the compiler cannot see is
 * the shape of the graph between packages that <em>do</em> depend on each other, which is
 * what the two rules here cover.
 *
 * <p>Both rules allow an empty match. They are prohibitions, and a prohibition over zero
 * classes holds; without this ArchUnit treats "matched nothing" as a failure, which would
 * make the rules unwritable until the first controller lands. The cost of that permission
 * is that a rule could quietly stop matching anything and still pass, so
 * {@link RulesFailWhenViolated} checks each rule against code that breaks it.
 */
class ModuleBoundariesHoldTest {

	private static final String ROOT_PACKAGE = "hu.bankmonitor.payments";

	/**
	 * Every package the design calls for, relative to {@link #ROOT_PACKAGE}. Not the same
	 * list as the slices: the application class sits in the root and belongs to no slice,
	 * and {@code transfers.checks} is folded into the {@code transfers} slice by the
	 * matching pattern below, which is the point of making it a sub-package.
	 */
	private static final List<String> DOCUMENTED_PACKAGES = List.of(
			"accounts", "transfers", "transfers.checks", "idempotency", "fx", "outbox",
			"stream", "mockfx", "common");

	/**
	 * Matches on role as well as on name. The suffix alone would be the whole rule in a
	 * codebase that never drifts, but it is the naming — not the boundary — that drifts
	 * first: an {@code AccountEndpoint} annotated {@code @RestController} is the same
	 * mistake wearing a different name, and it is the rename that would make this rule
	 * stop noticing.
	 */
	private static final ArchRule NO_CONTROLLER_REACHES_A_REPOSITORY = noClasses()
			.that().haveSimpleNameEndingWith("Controller")
			.or().areMetaAnnotatedWith(Controller.class)
			.should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
			.orShould().dependOnClassesThat().areAssignableTo(Repository.class)
			.because("a controller holding a repository has skipped the service that owns the transaction")
			.allowEmptyShould(true);

	private final JavaClasses productionClasses = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages(ROOT_PACKAGE);

	/**
	 * A package with no types in it is invisible to everything below, so the skeleton
	 * would be an empty assertion rather than a passing one. What puts these packages on
	 * the classpath today is their {@code package-info}: javac compiles one to a class
	 * file even when it carries nothing but Javadoc, which is the opposite of the usual
	 * advice that a {@code package-info.class} needs an annotation to exist.
	 */
	@Test
	@DisplayName("every package the design calls for exists")
	void everyPackageOfTheDesignExists() {
		assertThat(productionClasses.stream().map(JavaClass::getPackageName).distinct())
				.containsAll(DOCUMENTED_PACKAGES.stream().map(name -> ROOT_PACKAGE + "." + name).toList());
	}

	/**
	 * Cycle-freedom is the machine-checkable half of "dependencies run one way". The
	 * direction itself — {@code transfers} onto the others and never back — is a design
	 * choice ArchUnit cannot infer, but any attempt to point one back at
	 * {@code transfers} closes a loop and lands here.
	 */
	@Test
	@DisplayName("the slices are free of cycles")
	void theSlicesAreFreeOfCycles() {
		slicesOf(ROOT_PACKAGE).check(productionClasses);
	}

	@Test
	@DisplayName("no controller reaches a repository directly")
	void noControllerReachesARepository() {
		NO_CONTROLLER_REACHES_A_REPOSITORY.check(productionClasses);
	}

	private static ArchRule slicesOf(String rootPackage) {
		return slices()
				.matching(rootPackage + ".(*)..")
				.should().beFreeOfCycles()
				.allowEmptyShould(true);
	}

	/**
	 * The fixtures that break each rule on purpose live under
	 * {@code testsupport.boundaryviolations}, outside the packages the rules above scan.
	 */
	@Nested
	@DisplayName("the rules fail when violated")
	class RulesFailWhenViolated {

		private static final String VIOLATIONS_PACKAGE = "hu.bankmonitor.testsupport.boundaryviolations";

		@Test
		@DisplayName("two slices that depend on each other are reported as a cycle")
		void detectsACycleBetweenSlices() {
			JavaClasses cycle = importViolation("cycles");

			assertThatThrownBy(() -> slicesOf(VIOLATIONS_PACKAGE + ".cycles").check(cycle))
					.isInstanceOf(AssertionError.class)
					.hasMessageContaining("Cycle detected");
		}

		@Test
		@DisplayName("a controller holding a repository is reported, by name")
		void detectsAControllerReachingARepositoryByName() {
			JavaClasses leak = importViolation("layering.bysuffix");

			assertThatThrownBy(() -> NO_CONTROLLER_REACHES_A_REPOSITORY.check(leak))
					.isInstanceOf(AssertionError.class)
					.hasMessageContaining("LeakyController")
					.hasMessageContaining("LeakyRepository");
		}

		/** The half a suffix-only rule would miss, and the reason the rule is not one. */
		@Test
		@DisplayName("a controller holding a repository is reported, by role")
		void detectsAControllerReachingARepositoryByRole() {
			JavaClasses leak = importViolation("layering.byrole");

			assertThatThrownBy(() -> NO_CONTROLLER_REACHES_A_REPOSITORY.check(leak))
					.isInstanceOf(AssertionError.class)
					.hasMessageContaining("AccountEndpoint")
					.hasMessageContaining("AccountStore");
		}

		/** Without {@code DO_NOT_INCLUDE_TESTS}, which would filter out these fixtures. */
		private static JavaClasses importViolation(String violation) {
			return new ClassFileImporter().importPackages(VIOLATIONS_PACKAGE + "." + violation);
		}
	}
}
