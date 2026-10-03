package dev.treyer.sagapay.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/** Insert-only, enforced by the database role's grants. */
@Entity
@Table(name = "ledger_idempotency")
public class LedgerIdempotency {

    @EmbeddedId
    private LedgerIdempotencyId id;

    @Column(name = "result_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerIdempotency() {}

    public LedgerIdempotency(String transferId, LedgerOperation operation, String resultJson) {
        this.id = new LedgerIdempotencyId(transferId, operation);
        this.resultJson = Objects.requireNonNull(resultJson, "resultJson");
        this.createdAt = Instant.now();
    }

    public LedgerIdempotencyId getId() {
        return id;
    }

    public String getResultJson() {
        return resultJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

}
