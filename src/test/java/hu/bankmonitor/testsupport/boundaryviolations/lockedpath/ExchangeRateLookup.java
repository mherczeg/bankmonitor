package hu.bankmonitor.testsupport.boundaryviolations.lockedpath;

import org.springframework.web.client.RestClient;

/** The slow thing a lock must never be waiting on. Never wired into anything. */
public class ExchangeRateLookup {

	private final RestClient provider = RestClient.create();

	public String quote() {
		return provider.get().uri("/rates").retrieve().body(String.class);
	}
}
