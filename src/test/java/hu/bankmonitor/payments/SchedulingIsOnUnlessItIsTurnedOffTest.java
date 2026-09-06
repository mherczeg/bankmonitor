package hu.bankmonitor.payments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.ClassUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deployment gets a poller that runs by itself, and the test suite gets one switch that
 * takes the background actor away.
 *
 * <p>Both halves are load-bearing and neither is provable from the other. The outbox is only
 * a delivery mechanism if something drains it without being asked, so "the poller runs on a
 * schedule" has to be an assertion rather than an annotation somebody trusts — and
 * {@code @Scheduled} is inert without {@code @EnableScheduling}, which is a way of shipping
 * a poller that never runs and no test noticing. The other half is what every test in this
 * slice rests on: with the switch off there is nothing to race, so a row observed unsent is
 * unsent because the code left it that way and not because the assertion won.
 *
 * <p>Reaching into {@link ScheduledAnnotationBeanPostProcessor#getScheduledTasks()} is what
 * makes the first claim specific. The presence of the configuration alone would say only
 * that scheduling infrastructure exists; what is wanted is that <em>this</em> method on
 * <em>that</em> bean is registered with it, which is the difference between scheduling being
 * on and the poller being scheduled.
 *
 * <p>The poller is named as a string because it is package-private in the slice it belongs
 * to, and that is deliberate on both sides: design decision 30 keeps it unreachable from
 * here, and this test asserts about it from the outside as a scheduler would find it.
 *
 * <p>Two contexts, one per nesting, which is the price of the claim — design decision 25's
 * accounting. {@code NONE} keeps each of them to the cheapest boot that still has the
 * scheduler in it, since nothing here goes over HTTP.
 */
class SchedulingIsOnUnlessItIsTurnedOffTest {

	private static final String THE_OUTBOX_POLLER = "hu.bankmonitor.payments.outbox.OutboxPoller";

	private static final String THE_POLLING_METHOD = "publishUnsentEvents";

	/**
	 * What a deployment gets, and what the rest of the suite runs under except where a test
	 * says otherwise: the property is absent, and {@code matchIfMissing} decides.
	 */
	@Nested
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
	@DisplayName("with nothing turning it off")
	class WithNothingTurningItOff {

		@Autowired
		private ApplicationContext context;

		@Test
		@DisplayName("the scheduler is configured")
		void theSchedulerIsConfigured() {
			assertThat(context.getBeanNamesForType(SchedulingConfiguration.class)).isNotEmpty();
			assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isNotEmpty();
		}

		@Test
		@DisplayName("the outbox poller is a task the scheduler holds, not merely a bean")
		void theOutboxPollerIsATaskTheSchedulerHolds() {
			assertThat(scheduledMethodsOf(context)).contains(THE_OUTBOX_POLLER + "." + THE_POLLING_METHOD);
		}
	}

	/**
	 * Off, the poller is inert rather than absent — the bean is still there for a test to
	 * drive by hand, which is what a conditional on the poller itself would have taken away.
	 */
	@Nested
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
	@TestPropertySource(properties = SchedulingConfiguration.ENABLED + "=false")
	@DisplayName("with the property turning it off")
	class WithThePropertyTurningItOff {

		@Autowired
		private ApplicationContext context;

		@Test
		@DisplayName("the scheduler is not configured at all")
		void theSchedulerIsNotConfiguredAtAll() {
			assertThat(context.getBeanNamesForType(SchedulingConfiguration.class)).isEmpty();
			assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
		}

		/**
		 * The half that would survive somebody re-enabling scheduling by another route: no
		 * scheduling infrastructure means no registered task, whoever registered it.
		 */
		@Test
		@DisplayName("nothing is scheduled, the outbox poller included")
		void nothingIsScheduled() {
			assertThat(scheduledMethodsOf(context)).isEmpty();
		}
	}

	/**
	 * Every {@code @Scheduled} method the context actually registered, as
	 * {@code <declaring class>.<method>}. Empty when no scheduling infrastructure was
	 * configured, because then nothing read the annotation.
	 */
	private static List<String> scheduledMethodsOf(ApplicationContext context) {
		return context.getBeanProvider(ScheduledAnnotationBeanPostProcessor.class).stream()
				.flatMap(scheduler -> scheduler.getScheduledTasks().stream())
				.map(SchedulingIsOnUnlessItIsTurnedOffTest::nameOf)
				.toList();
	}

	private static String nameOf(ScheduledTask task) {
		if (task.getTask().getRunnable() instanceof ScheduledMethodRunnable method) {
			return ClassUtils.getUserClass(method.getTarget()).getName() + "." + method.getMethod().getName();
		}
		return task.toString();
	}
}
