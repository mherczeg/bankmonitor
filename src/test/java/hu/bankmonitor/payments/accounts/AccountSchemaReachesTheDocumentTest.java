package hu.bankmonitor.payments.accounts;

import hu.bankmonitor.testsupport.BootedApplicationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the vocabulary this endpoint declares actually reaches the OpenAPI document.
 *
 * <p>Design decision 26 has the frontend generating its types from
 * {@code /v3/api-docs}, which is the whole reason {@code currency} is the
 * {@link hu.bankmonitor.payments.common.Currency} enum rather than a string — and the
 * reason this slice pays for it, since Jackson then refuses an unknown name before Bean
 * Validation runs and the error contract had to widen to name the field. Typing the field
 * for a benefit nothing checks would leave that cost buying nothing.
 *
 * <p>{@code OpenApiDocumentSpikeTest} settles the ecosystem bet that springdoc introspects
 * at all. This is the narrower claim the design rests on: that it introspects <em>these</em>
 * types — package-private records among them — into a closed set the frontend can read.
 * Changing {@code currency} to a {@code String} leaves every other test in the suite green.
 */
class AccountSchemaReachesTheDocumentTest extends BootedApplicationTest {

	@Test
	@DisplayName("the document offers the three currencies as a closed set, on both schemas")
	void publishesTheCurrenciesAsAClosedSet() {
		client().get().uri("/v3/api-docs")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.components.schemas.CreateAccountRequest.properties.currency.enum")
				.value(List.class, currencies ->
						assertThat(currencies).containsExactlyInAnyOrder("EUR", "USD", "HUF"))
				.jsonPath("$.components.schemas.AccountResponse.properties.currency.enum")
				.value(List.class, currencies ->
						assertThat(currencies).containsExactlyInAnyOrder("EUR", "USD", "HUF"));
	}

	/**
	 * springdoc reads {@code required} off constraint annotations, and a response is never
	 * validated — so left alone, {@link AccountResponse} publishes a schema whose every
	 * member is optional, and ticket 32's generated types accept a mock that omits the
	 * balance. The record says so itself with {@code @Schema(requiredProperties = ...)},
	 * and this compares that list against the record's own components so the two cannot
	 * drift apart.
	 */
	@Test
	@DisplayName("the response schema says every member is always sent")
	void publishesEveryResponseMemberAsRequired() {
		List<String> everyComponent = Arrays.stream(AccountResponse.class.getRecordComponents())
				.map(RecordComponent::getName)
				.toList();

		client().get().uri("/v3/api-docs")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.components.schemas.AccountResponse.required")
				.value(List.class, required ->
						assertThat(required).containsExactlyInAnyOrderElementsOf(everyComponent));
	}

	/**
	 * The constraints are part of the published contract too: ticket 39's form is meant to
	 * refuse a negative opening balance before it costs a round trip, and it reads that
	 * rule from here rather than restating it.
	 */
	@Test
	@DisplayName("the request schema publishes its constraints, not just its field names")
	void publishesTheConstraintsOnTheRequest() {
		client().get().uri("/v3/api-docs")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.components.schemas.CreateAccountRequest.properties.openingBalanceMinorUnits.minimum")
				.isEqualTo(0)
				.jsonPath("$.components.schemas.CreateAccountRequest.required")
				.value(List.class, required ->
						assertThat(required).containsExactlyInAnyOrder("currency", "openingBalanceMinorUnits"));
	}
}
