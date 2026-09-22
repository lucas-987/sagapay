package dev.treyer.sagapay.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One outbox row per saga transition, drained to Kafka by {@code OutboxPoller}
 * (§9). {@code payload}/{@code headers} are pre-serialized JSON text (same
 * convention as the ledger's {@code ledger_idempotency.result_json} — the caller
 * serializes via {@code JsonMapper}, this entity just carries the string) bound to
 * the {@code jsonb} columns via {@code @JdbcTypeCode(JSON)}, same reasoning as
 * {@code Transfer.status}'s {@code NAMED_ENUM} mapping for its native enum column. */
@Entity
@Table(name = "outbox")
public class OutboxRow {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 32)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 32)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", nullable = false, columnDefinition = "jsonb")
    private String headers;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** Required by JPA (field access) — never called by business code. */
    protected OutboxRow() {}

    public OutboxRow(UUID id, String aggregateType, UUID aggregateId, String eventType,
                      String payload, String headers) {
        this.id = Objects.requireNonNull(id, "id");
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.headers = headers == null ? "{}" : headers;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public String getHeaders() {
        return headers;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    // No setPublishedAt(): marked published through a conditional @Modifying
    // query, same reasoning as Transfer's lack of a status setter.
}
