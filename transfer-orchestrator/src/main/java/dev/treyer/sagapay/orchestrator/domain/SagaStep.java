package dev.treyer.sagapay.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One row per saga transition (RESERVE/POST, OK/FAILED) — what {@code GET
 * /v1/transfers/{id}} exposes as {@code TransferDetail.steps} (§8). {@code
 * transfer_id} is a real FK (both rows live in this same orchestrator_svc
 * database, unlike the ledger's correlation-only ids). */
@Entity
@Table(name = "saga_steps")
public class SagaStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Column(name = "at", nullable = false, updatable = false)
    private Instant at;

    @Column(name = "step", nullable = false, updatable = false, length = 16)
    private String step;

    @Column(name = "outcome", nullable = false, updatable = false, length = 16)
    private String outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", updatable = false, columnDefinition = "jsonb")
    private String detail;

    /** Required by JPA (field access) — never called by business code. */
    protected SagaStep() {}

    public SagaStep(UUID transferId, String step, String outcome, String detail) {
        this.transferId = Objects.requireNonNull(transferId, "transferId");
        this.step = Objects.requireNonNull(step, "step");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.detail = detail;
        this.at = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public Instant getAt() {
        return at;
    }

    public String getStep() {
        return step;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getDetail() {
        return detail;
    }
}
