package dev.treyer.sagapay.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Account ids are plain values: the accounts live in the ledger's database. */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "sender_id", nullable = false, updatable = false)
    private UUID senderId;

    @Column(name = "sender_account_id", nullable = false, updatable = false)
    private UUID senderAccountId;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    @Column(name = "recipient_account_id", nullable = false, updatable = false)
    private UUID recipientAccountId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 20, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "note", updatable = false, length = 200)
    private String note;

    // NAMED_ENUM binds the field to the native Postgres enum type.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private TransferStatus status;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "failure_reason", length = 64)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transfer() {}

    public Transfer(UUID id, UUID idempotencyKey, UUID senderId, UUID senderAccountId,
                     UUID recipientId, UUID recipientAccountId, BigDecimal amount, String currency, String note) {
        this.id = Objects.requireNonNull(id, "id");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.senderId = Objects.requireNonNull(senderId, "senderId");
        this.senderAccountId = Objects.requireNonNull(senderAccountId, "senderAccountId");
        this.recipientId = Objects.requireNonNull(recipientId, "recipientId");
        this.recipientAccountId = Objects.requireNonNull(recipientAccountId, "recipientAccountId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.note = note;
        this.status = TransferStatus.INITIATED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public UUID getSenderAccountId() {
        return senderAccountId;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public UUID getRecipientAccountId() {
        return recipientAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getNote() {
        return note;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // No status setter: transitions go through conditional updates, which a
    // load-mutate-save would race.
}
