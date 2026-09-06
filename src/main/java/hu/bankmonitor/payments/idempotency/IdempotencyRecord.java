package hu.bankmonitor.payments.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;

/**
 * One client-supplied Idempotency Key, how far the work behind it has got, and the
 * response a repeat of it is owed.
 *
 * <p>The row is the lock. A unique constraint on {@link #getIdempotencyKey() the key} is
 * what serialises two requests arriving at once: both reach the insert, the database
 * refuses one of them, and the refusal — not a preceding read — is how the duplicate is
 * discovered. No application-level lock is involved anywhere in this slice.
 *
 * <p><b>The key is not the primary key, which is not an oversight.</b> Spring Data decides
 * whether {@code save} inserts or merges from whether the identifier is already set, so an
 * entity carrying an assigned key would be saved through {@code merge} — a {@code SELECT}
 * before every {@code INSERT}, and a silent {@code UPDATE} over a live claim when the row
 * it found belongs to somebody else's in-flight request. The surrogate identifier keeps
 * the claim a bare insert, and the named unique constraint keeps the mutual exclusion.
 *
 * <p>There is deliberately no timestamp. The status is the single answer to how far a
 * claim has got, and nothing here reads a clock yet; the reaper that would sweep claims
 * stranded by a crash is deferred, and is the ticket that should add the column with a
 * reader to shape it. {@code docs/deferred.md} has the reasoning.
 *
 * <p>There is also deliberately no mutator. Every transition this record makes is a
 * conditional or guarded update issued by {@link IdempotencyClaims}, because each one has
 * to be atomic against a concurrent duplicate — a setter would offer a second way to make
 * the same transition that quietly is not.
 */
@Entity
@Table(name = "idempotency_records")
class IdempotencyRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(length = 36)
	private String idempotencyKey;

	@Column(length = 64)
	private String payloadHash;

	// If left unannotated, JPA stores the status by ordinal. See design decision 29.
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private IdempotencyStatus status;

	@Column(length = 4000)
	private String responseBody;

	protected IdempotencyRecord() {
		// required by JPA
	}

	/**
	 * Claims a key, which is the only way a record comes into being: {@code IN_PROGRESS},
	 * with no response yet and the hash of the payload the claim was made for.
	 *
	 * <p>The hash is stored rather than the payload because the only question ever asked
	 * of it is whether a later request carrying this key means the same thing.
	 */
	IdempotencyRecord(String idempotencyKey, String payloadHash) {
		this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "a claim is made on a key");
		this.payloadHash = Objects.requireNonNull(payloadHash, "a claim is made for a payload");
		this.status = IdempotencyStatus.IN_PROGRESS;
	}

	Long getId() {
		return id;
	}

	/** The value the client sent as {@code X-Idempotency-Key}. Unique across the table. */
	String getIdempotencyKey() {
		return idempotencyKey;
	}

	/**
	 * What the request said, reduced to the one comparison made against it: a repeat of
	 * this key carrying a different hash is a client mistake, not a retry.
	 */
	String getPayloadHash() {
		return payloadHash;
	}

	IdempotencyStatus getStatus() {
		return status;
	}

	/** The response the original request produced, present only once it has {@code SUCCEEDED}. */
	String getResponseBody() {
		return responseBody;
	}
}
