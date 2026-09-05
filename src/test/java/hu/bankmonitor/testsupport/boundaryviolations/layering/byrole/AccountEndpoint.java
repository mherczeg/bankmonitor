package hu.bankmonitor.testsupport.boundaryviolations.layering.byrole;

import org.springframework.web.bind.annotation.RestController;

/**
 * The same leak as {@code bysuffix.LeakyController}, named so that a suffix rule would
 * miss it. See {@code ModuleBoundariesHoldTest.RulesFailWhenViolated}.
 */
@RestController
public class AccountEndpoint {

	AccountStore accounts;
}
