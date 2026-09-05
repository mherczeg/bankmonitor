package hu.bankmonitor.testsupport.boundaryviolations.layering.bysuffix;

/**
 * A controller reaching past its service straight into persistence. See
 * {@code ModuleBoundariesHoldTest.RulesFailWhenViolated}.
 */
public class LeakyController {

	LeakyRepository repository;
}
