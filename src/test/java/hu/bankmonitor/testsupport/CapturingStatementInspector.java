package hu.bankmonitor.testsupport;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every statement Hibernate sends to the driver, in order, lowercased and trimmed.
 *
 * <p>The only place a claim about the SQL can be checked. {@code for update} is appended by
 * the dialect, so neither the entity nor the repository method mentions it; and the order two
 * statements were issued in is not recoverable from the rows they left behind. An assertion
 * phrased through the thing under test would agree with whatever that thing decided to do.
 *
 * <p>Register it with {@code @Import(CapturingStatementInspector.class)}. Hibernate accepts
 * exactly one {@link StatementInspector}, so a test class cannot have both this and
 * {@link RowLockBarrier} — which is no loss, as one asks what was issued and the other
 * changes when.
 *
 * <p>Call {@link #forget} at the start of anything that counts statements: the application
 * context is cached across test classes, so the recording outlives any one of them.
 */
public final class CapturingStatementInspector implements StatementInspector, HibernatePropertiesCustomizer {

	private final List<String> statements = new CopyOnWriteArrayList<>();

	@Override
	public void customize(Map<String, Object> hibernateProperties) {
		hibernateProperties.put(AvailableSettings.STATEMENT_INSPECTOR, this);
	}

	@Override
	public String inspect(String sql) {
		statements.add(sql.toLowerCase(Locale.ROOT).trim());
		return sql;
	}

	public List<String> captured() {
		return List.copyOf(statements);
	}

	public void forget() {
		statements.clear();
	}
}
