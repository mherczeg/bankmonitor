package hu.bankmonitor.payments.fx;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * The one client in this application that talks to somebody else, built from the settings
 * that say where it talks and the two timeouts that keep it from talking forever.
 *
 * <p>{@code @EnableResilientMethods} is application-wide — it registers a bean
 * post-processor, not a slice-local one — but it is declared here rather than in the root
 * package because the only retryable method in the system is a few lines away, and a
 * second slice wanting retry should find the annotation by finding the code that already
 * uses it. Spring Boot does not auto-configure it; without this line {@code @Retryable}
 * is an annotation nothing reads, and the first attempt is the only attempt, silently.
 *
 * <p>The reasoning, and the alternatives rejected, are in
 * {@code docs/design-decisions/25-exchange-rate-client.md}.
 */
@Configuration
@EnableResilientMethods
@EnableConfigurationProperties(ExchangeRateSettings.class)
class ExchangeRateConfiguration {

	@Bean
	RestClient exchangeRateClient(ExchangeRateSettings settings) {
		return RestClient.builder()
				.baseUrl(settings.baseUrl().toString())
				.requestFactory(boundedByTimeouts(settings))
				.build();
	}

	/**
	 * Both timeouts, on the JDK's own HTTP client.
	 *
	 * <p>Without them "the provider is slow" has no upper bound, and §27's failure mode
	 * arrives: a request parks indefinitely while holding an in-progress Idempotency Key,
	 * so an unreliable third party becomes a Transfer that can never be retried.
	 *
	 * <p>The connect timeout belongs to the client and the read timeout to the request
	 * factory, because the JDK client has no read timeout of its own — Spring's factory
	 * implements it as a watchdog over the whole response.
	 *
	 * <p>The executor is virtual for the same reason {@code spring.threads.virtual.enabled}
	 * is set: under the {@code mock-fx} profile this application is its own provider, and
	 * the JDK client's default executor is a pool of platform threads sitting in the middle
	 * of that self-call.
	 */
	private static ClientHttpRequestFactory boundedByTimeouts(ExchangeRateSettings settings) {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(settings.connectTimeout())
				.executor(new VirtualThreadTaskExecutor("fx-client-"))
				.build();

		JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(httpClient);
		requests.setReadTimeout(settings.readTimeout());
		return requests;
	}
}
