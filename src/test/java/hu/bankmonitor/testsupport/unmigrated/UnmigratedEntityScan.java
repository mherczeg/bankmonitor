package hu.bankmonitor.testsupport.unmigrated;

import hu.bankmonitor.payments.GlobalPaymentServiceApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;

/**
 * Puts {@link EntityWithNoTable} on the entity scan alongside the application's own
 * package, which {@code @EntityScan} replaces rather than extends.
 *
 * <p>A top-level class rather than one nested in the test that uses it: Spring Boot's test
 * support treats a {@code @Configuration} class nested in a test as <em>the</em>
 * configuration for that test's context, so nesting it would quietly replace the
 * application under test.
 *
 * <p>This package holds nothing else, and that is the constraint it exists for — the scan
 * has to reach one entity Flyway knows nothing about, not every throwaway entity in
 * {@code testsupport}, or Hibernate would report whichever of them it happened to meet
 * first. The isolation runs one way only: {@code RecordAsEmbeddableSpikeTest} scans
 * {@code testsupport} and its subpackages, so it creates {@link EntityWithNoTable}'s table
 * too. That is harmless — the spike owns its schema under {@code create-drop} — and it
 * disappears when the spikes do.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = {GlobalPaymentServiceApplication.class, EntityWithNoTable.class})
public class UnmigratedEntityScan {
}
