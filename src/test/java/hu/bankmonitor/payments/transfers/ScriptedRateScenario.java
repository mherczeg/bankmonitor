package hu.bankmonitor.payments.transfers;

import hu.bankmonitor.testsupport.ScriptedExchangeRates;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * A {@link TransferScenario} whose Exchange Rate provider answers what the test scripted — the
 * base for every class that requests a cross-Currency Transfer over HTTP.
 *
 * <p><b>The {@code @Import} lives here rather than on each subclass so that sharing one Spring
 * context is structural.</b> Spring's context cache keys on the configuration, so three classes
 * carrying identical annotations happened to share one; three classes inheriting them cannot
 * stop sharing one by accident. The saving is real — a booted context per class is the most
 * expensive thing a suite this size can buy — and the cost of losing it is invisible, because
 * a second context is slower and not wrong.
 *
 * <p>What that sharing costs is a singleton whose scripted rate, pending failures and record of
 * what it was asked outlive the test that set them, so the reset below is not optional. It is
 * separate from the subclasses'
 * {@code @BeforeEach} because JUnit runs a superclass's first: every subclass scripts its rates
 * knowing they have already been forgotten.
 *
 * <p>{@link ScriptedExchangeRates} is a witness rather than a stub, and the difference is why
 * this is a real bean substituted by {@code @Primary} instead of a {@code @MockitoBean}: some
 * of the claims made against it are about the caller's thread at the moment of the call, and
 * there is nowhere to observe that but inside the provider.
 */
@Import(ScriptedExchangeRates.class)
abstract class ScriptedRateScenario extends TransferScenario {

	@Autowired
	ScriptedExchangeRates rates;

	@BeforeEach
	void forgetWhatTheLastTestScripted() {
		rates.forgetEverything();
	}
}
