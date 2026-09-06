package hu.bankmonitor.payments.accounts;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import hu.bankmonitor.testsupport.boundaryviolations.lockedpath.LockHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The constraint that makes pessimistic locking affordable: a transaction holding row
 * locks waits on nothing slower than the database.
 *
 * <p>Design decision 4 fetches the Exchange Rate in a phase of its own precisely so that
 * it is not inside these locks, and design decision 6 says that placement is what makes
 * the whole locking design viable. The half of it that lives in {@code transfers} — that
 * the rate is fetched with no transaction open — is ticket 26's to assert. What is
 * assertable here is the other half: that the operation holding the locks cannot reach
 * anything but the database, however far down its call graph the reach would be.
 *
 * <p>The reachable set is walked here rather than left to ArchUnit's
 * {@code transitivelyDependOnClassesThat}, which follows dependencies into the JDK as
 * well and so finds a path from every class to {@code java.net.URL} by way of
 * {@code java.lang.Class}. Walking only the application's own classes is both the
 * question worth asking and the only one with a useful answer: a call to a provider is a
 * class of ours that holds the client.
 *
 * <p>Two limits of the technique, stated because neither is visible from a green tick:
 *
 * <ul>
 * <li><b>The walk stops at an interface.</b> A dependency on one yields its declarations,
 * never its implementations, so a port whose HTTP implementation lives elsewhere is
 * invisible to the hop that would find it. What covers that case is
 * {@link #IO_BEYOND_THE_DATABASE} naming the {@code fx} and {@code mockfx} <em>packages</em>
 * rather than any client type: the port itself is the forbidden dependency, whichever
 * class implements it. A port added outside those packages would slip through, which is
 * the argument for keeping provider slices where §30 puts them.
 * <li><b>{@link #LOCK_HOLDERS} is a list, not a query.</b> Nothing derives it, so a future
 * transactional method that takes these locks is covered only once it is named here —
 * see {@link #theLockHolderReachesNothingButTheDatabase}.
 * </ul>
 */
class LockedPathTouchesOnlyTheDatabaseTest {

	/**
	 * Sockets, files, the servlet API, Spring's HTTP packages, and the two slices that put
	 * a provider behind them — ticket 25 makes the {@code fx} call a real one, and no
	 * amount of it belongs under a lock.
	 *
	 * <p>Deliberately a list of packages rather than an attempt at every HTTP client in
	 * existence: naming Apache HttpClient and OkHttp would suggest the list is exhaustive
	 * when the next dependency added to the {@code pom} would make it false again. What is
	 * exhaustive is the {@code fx} and {@code mockfx} entries — a provider call in this
	 * application lives in one of those two by §30, whatever it is built out of.
	 */
	private static final DescribedPredicate<JavaClass> IO_BEYOND_THE_DATABASE = resideInAnyPackage(
			"java.net..",
			"java.io..",
			"java.nio.file..",
			"jakarta.servlet..",
			"org.springframework.web..",
			"org.springframework.http..",
			"hu.bankmonitor.payments.fx..",
			"hu.bankmonitor.payments.mockfx..");

	/**
	 * Every operation that takes these row locks: the one that exports them, and the two that
	 * open a transaction the locks are held in — the reservation that requests a Transfer and
	 * the Verdict that ends one.
	 *
	 * <p>Deliberately not "everything that calls {@link AccountLocking}". The caller is
	 * where the transaction is opened, so widening this to callers looks stricter and is
	 * wrong: ticket 26 gives {@code FundsReservation} an Exchange Rate port to call in the
	 * phase <em>before</em> the transaction, which is precisely what design decision 4 asks
	 * for, and this rule cannot tell the two phases apart. When that arrives the entry below
	 * has to come out, and ticket 26 asserts the phase ordering the other way, in
	 * {@code transfers}.
	 *
	 * <p>Class names rather than {@code Class} literals, because {@code FundsReservation} is
	 * package-private in {@code transfers} by §30 and so cannot be named from this package —
	 * which is the boundary working, not an obstacle to route around by widening its
	 * visibility for a test. A name that matches nothing fails loudly in
	 * {@link JavaClasses#get(String)} rather than quietly asserting over an empty set.
	 */
	private static final List<String> LOCK_HOLDERS = List.of(
			AccountLocking.class.getName(),
			"hu.bankmonitor.payments.transfers.FundsReservation",
			"hu.bankmonitor.payments.transfers.VerdictRecording");

	@Test
	@DisplayName("the operation that holds the locks reaches nothing but the database")
	void theLockHolderReachesNothingButTheDatabase() {
		JavaClasses productionClasses = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("hu.bankmonitor.payments");

		assertThat(LOCK_HOLDERS)
				.allSatisfy(lockHolder -> assertThat(ioReachableFrom(lockHolder, productionClasses))
						.as("a transaction holding account locks must never wait on a slow provider")
						.isEmpty());
	}

	/**
	 * The shape the mistake actually takes, and the reason the walk follows hops at all: a
	 * check reading only direct dependencies would pass {@link LockHolder}, which holds no
	 * client itself and calls one that does.
	 *
	 * <p>Be clear about what this does and does not establish. It proves the <em>walk</em>
	 * is falsifiable — that the search above returns a finding when a finding exists —
	 * which is what stops the green tick above from being the green tick of a rule that
	 * stopped matching. It does not prove the production invocation is falsifiable, because
	 * it runs over a different import scope from a different origin. Nothing short of
	 * putting a client in the real locked path would prove that, and the cost of doing so
	 * permanently is a class in {@code accounts} that exists only to be wrong.
	 *
	 * <p>The count of entries is not pinned: one call through {@code RestClient}'s fluent
	 * builder depends on each interface in the chain, so the list grows and shrinks with a
	 * Spring upgrade. What the rule claims is that the reach is found and blamed on the
	 * class that made it.
	 */
	@Test
	@DisplayName("a lock holder that reaches an HTTP client one hop down is reported")
	void detectsIoOneHopBelowTheLockHolder() {
		JavaClasses violation = new ClassFileImporter()
				.importPackages("hu.bankmonitor.testsupport.boundaryviolations.lockedpath");

		assertThat(ioReachableFrom(LockHolder.class.getName(), violation))
				.as("the reach is real, and it is attributed to the class one hop down")
				.contains("ExchangeRateLookup depends on org.springframework.web.client.RestClient")
				.allSatisfy(reach -> assertThat(reach).startsWith("ExchangeRateLookup depends on"));
	}

	/**
	 * Every forbidden dependency of every class the lock holder can reach, as text a
	 * failure message can name the culprit with.
	 */
	private static List<String> ioReachableFrom(String lockHolder, JavaClasses ourClasses) {
		return reachableFrom(ourClasses.get(lockHolder), ourClasses).stream()
				.flatMap(onThePath -> onThePath.getDirectDependenciesFromSelf().stream())
				.filter(dependency -> IO_BEYOND_THE_DATABASE.test(targetOf(dependency)))
				.map(dependency -> dependency.getOriginClass().getSimpleName()
						+ " depends on " + targetOf(dependency).getFullName())
				.distinct()
				.toList();
	}

	/** Breadth-first over {@code ourClasses} only, so the walk stops at the framework. */
	private static Set<JavaClass> reachableFrom(JavaClass origin, JavaClasses ourClasses) {
		Set<JavaClass> reached = new LinkedHashSet<>();
		Deque<JavaClass> frontier = new ArrayDeque<>(List.of(origin));
		while (!frontier.isEmpty()) {
			JavaClass next = frontier.removeFirst();
			if (!reached.add(next)) {
				continue;
			}
			next.getDirectDependenciesFromSelf().stream()
					.map(LockedPathTouchesOnlyTheDatabaseTest::targetOf)
					.filter(target -> ourClasses.contain(target.getName()))
					.forEach(frontier::addLast);
		}
		return reached;
	}

	/** An array dependency reports the array type, and its package is the element's. */
	private static JavaClass targetOf(Dependency dependency) {
		return dependency.getTargetClass().getBaseComponentType();
	}
}
