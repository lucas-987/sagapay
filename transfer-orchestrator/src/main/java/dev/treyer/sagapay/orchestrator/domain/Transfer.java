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

/** A class, not a record: JPA requires a no-arg constructor plus fields mutable by
 * reflection (same reasoning as the ledger's {@code Account}). Raw UUIDs for
 * sender/recipient/account ids, no {@code @ManyToOne}: those rows live in the
 * ledger's own database, another service — a correlation id, not a foreign key. */
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

    // status is a native Postgres ENUM (transfer_status), not VARCHAR like the
    // ledger's ReservationStatus: @JdbcTypeCode(NAMED_ENUM), on top of
    // @Enumerated(STRING), tells Hibernate to bind/read this field as that named
    // database enum type — save()/findById() round-trip verified in
    // TransferRepositoryTest against a real Postgres instance.
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

    /** Required by JPA (field access) — never called by business code. */
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

    // No setStatus()/setReservationId(): transitions go through conditional
    // @Modifying queries (WHERE status = ...), same reasoning as
    // Account.setBalance()/Reservation's lack of a status setter in M1 — a public
    // setter would invite a load/mutate/save race instead of an atomic guard.
}
