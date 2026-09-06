package hu.bankmonitor.payments.mockfx;

import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The stand-in Exchange Rate provider, answering on a real socket.
 *
 * <p>It is an endpoint rather than a stubbed bean because a bean sits <em>above</em> the
 * HTTP client: none of the timeouts, retries or error mapping that ticket 25 builds would
 * run against one, and the thing being demonstrated would be the thing mocked out. The
 * cost of that choice is that the application now calls itself over HTTP, which is what
 * makes {@code spring.threads.virtual.enabled} load-bearing — on a bounded pool an
 * inbound request can hold a thread while waiting for a second thread to serve its own
 * outbound call.
 *
 * <p>Its errors are answered here, on the controller, rather than by a
 * {@code @ControllerAdvice}. A handler declared on the controller wins over every advice
 * in the application without anything having to be ordered, which is how the provider
 * keeps its own error shape while this application's global advice stays global for
 * everything that is genuinely ours.
 *
 * <p>{@code @Hidden} keeps it out of {@code /v3/api-docs}, which is a layer of ours as
 * much as the security chain is: the frontend generates its types from that document
 * against a running backend, so without this a developer who ran with this profile and
 * regenerated would commit a third party's endpoint — carrying our problem document as
 * its error response — into {@code schema.gen.ts}.
 */
@Hidden
@RestController
@Profile(MockExchangeRateProvider.PROFILE)
class MockExchangeRateController {

	static final String RATES_PATH = MockExchangeRateProvider.NAMESPACE + "/fx/rates";

	private static final Logger log = LoggerFactory.getLogger(MockExchangeRateController.class);

	private static final ProviderFault UNAVAILABLE = new ProviderFault(
			"rate_service_unavailable", "The rate service is temporarily unavailable. Try again shortly.");

	private static final ProviderFault UNQUOTED_CURRENCY = new ProviderFault(
			"unquoted_currency", "This service quotes %s, in uppercase."
					.formatted(String.join(", ", new TreeSet<>(QuotedRates.SUPPORTED_CURRENCIES))));

	private final FlakinessSettings flakiness;

	MockExchangeRateController(FlakinessSettings flakiness) {
		this.flakiness = flakiness;
	}

	/**
	 * Quotes {@code quote} per one unit of {@code base}, slowly, and sometimes not at all.
	 *
	 * <p>The stall comes first so that a failure is as slow as a success. A provider whose
	 * errors arrived promptly would let a client's timeout and its retry budget be tuned
	 * independently, which is not the situation an unreliable third party actually puts
	 * you in.
	 */
	@GetMapping(RATES_PATH)
	ResponseEntity<Object> rate(@RequestParam String base, @RequestParam String quote) {
		stallForTheConfiguredLatency();
		if (thisRequestIsOneOfTheFailures()) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(UNAVAILABLE);
		}
		Optional<BigDecimal> rate = QuotedRates.between(base, quote);
		if (rate.isEmpty()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(UNQUOTED_CURRENCY);
		}
		return ResponseEntity.ok(new RateQuote(base, quote, rate.get()));
	}

	/**
	 * The framework's own rejection of an incomplete request, answered in this provider's
	 * words. Without this the application's advice would answer it, and a caller would
	 * read our problem document at a third party's URL.
	 */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	ResponseEntity<ProviderFault> missingParameter(MissingServletRequestParameterException missing) {
		return ResponseEntity.badRequest().body(new ProviderFault(
				"missing_parameter", "Query parameter '%s' is required.".formatted(missing.getParameterName())));
	}

	/**
	 * The backstop that makes "its errors are its own" true of everything, rather than of
	 * the failures anticipated above. Anything reaching here is a defect in this class,
	 * and the provider still owes the caller an answer in its own shape.
	 *
	 * <p>It is logged before it is swallowed. The response deliberately says nothing about
	 * what went wrong — a third party's would not — so without this line the one place the
	 * defect could be seen is the place that discards it.
	 */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ProviderFault> anythingElse(Exception failure) {
		log.error("The stand-in FX provider failed to answer a request", failure);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new ProviderFault("rate_service_error", "The rate service failed to answer."));
	}

	private boolean thisRequestIsOneOfTheFailures() {
		return ThreadLocalRandom.current().nextDouble() < flakiness.failureRate();
	}

	private void stallForTheConfiguredLatency() {
		try {
			Thread.sleep(flakiness.latency());
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while pretending to be slow", interrupted);
		}
	}
}
