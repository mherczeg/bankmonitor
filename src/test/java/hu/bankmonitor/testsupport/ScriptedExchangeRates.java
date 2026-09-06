package hu.bankmonitor.testsupport;

import hu.bankmonitor.payments.common.Currency;
import hu.bankmonitor.payments.fx.ExchangeRate;
import hu.bankmonitor.payments.fx.ExchangeRateProvider;
import hu.bankmonitor.payments.fx.ExchangeRateUnavailableException;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Exchange Rate provider, told what to answer and asked afterwards what it was asked.
 *
 * <p>It replaces the HTTP one for the tests that drive a cross-Currency Transfer end to end.
 * The stand-in provider under the {@code mock-fx} profile would serve a rate, but it picks
 * its own and fails at a configured probability, and neither is a thing a test can assert a
 * converted amount against. This one is quoted at, so the credited figure in the response is
 * checkable by hand.
 *
 * <p>Substituted for the real bean by {@code @Primary} and an {@code @Import}, rather than by
 * {@code @MockitoBean}: what several of these tests need is not a stub but a witness — a
 * provider that remembers whether it was called at all, and what the caller's transaction
 * looked like at the moment it was.
 *
 * <p>Every test class using it shares one instance, because Spring caches one context for
 * all of them. {@link #forgetEverything()} therefore has to run before each test rather than
 * being left to the next one to overwrite.
 */
@Primary
public class ScriptedExchangeRates implements ExchangeRateProvider {

	/**
	 * When every quote here was fetched. Fixed, so that the timestamp a response reports can
	 * be asserted against a literal rather than against a window — which is the whole reason
	 * this provider exists rather than a mock returning {@code Instant.now()}.
	 *
	 * <p>Deliberately unrelated to the requesting Clock, and it has to be: a real quote is
	 * fetched in phase two and so lands <em>after</em> the instant the request was stamped
	 * with, and pinning both to one Clock would make a test that mixed them up green.
	 */
	public static final Instant QUOTED_AT = Instant.parse("2026-09-06T09:40:58Z");

	/**
	 * How many attempts a failure from here claims to have made. A real outage reports the
	 * whole retry budget of {@code payments.fx.*}, and the number reaches the client, so a
	 * stand-in answering {@code 0} would make the response say the provider was never tried.
	 */
	public static final int ATTEMPTS = 3;

	private final AtomicReference<BigDecimal> rate = new AtomicReference<>(BigDecimal.ONE);

	private final AtomicInteger requestsToFail = new AtomicInteger();

	private final List<String> pairsAsked = new CopyOnWriteArrayList<>();

	private final AtomicBoolean sawAnOpenTransaction = new AtomicBoolean();

	@Override
	public ExchangeRate exchangeRateFor(Currency base, Currency quote) {
		pairsAsked.add(base + "/" + quote);
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			sawAnOpenTransaction.set(true);
		}
		if (requestsToFail.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
			throw new ExchangeRateUnavailableException(base, quote, ATTEMPTS,
					new IllegalStateException("the provider did not answer"));
		}
		return new ExchangeRate(base, quote, rate.get(), QUOTED_AT);
	}

	/** Destination Currency units per one of the source's, for every quote until the next call. */
	public void quoteAt(String destinationUnitsPerSourceUnit) {
		rate.set(new BigDecimal(destinationUnitsPerSourceUnit));
	}

	/**
	 * Fail the next {@code requests} quotes the way an exhausted retry budget does, and answer
	 * normally after that. Counted rather than latched, because the claim worth making is that
	 * the <em>same</em> Idempotency Key resubmitted after an outage executes — which needs the
	 * provider to have recovered by the second request.
	 */
	public void failTheNext(int requests) {
		requestsToFail.set(requests);
	}

	/** Which pairs were asked for, in order, as {@code BASE/QUOTE}. */
	public List<String> pairsAsked() {
		return List.copyOf(pairsAsked);
	}

	/**
	 * Whether any quote was fetched inside a transaction — the arrangement design decision 6
	 * exists to prevent, because a transaction here is one holding two Account row locks.
	 */
	public boolean sawAnOpenTransaction() {
		return sawAnOpenTransaction.get();
	}

	public void forgetEverything() {
		rate.set(BigDecimal.ONE);
		requestsToFail.set(0);
		pairsAsked.clear();
		sawAnOpenTransaction.set(false);
	}
}
