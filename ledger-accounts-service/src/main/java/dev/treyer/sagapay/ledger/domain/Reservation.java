package dev.treyer.sagapay.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A hold on a sending account while a transfer is being decided. */
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // Raw UUID, not @ManyToOne Account: avoids an unnecessary proxy load on the
    // checkAndReserve/postTransfer hot path.
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    // NOT a DB FK despite the name: the `transfers` row lives in orchestrator_svc,
    // another service's database — a correlation id, not a foreign key.
    @Column(name = "transfer_id", nullable = false, updatable = false, length = 64)
    private String transferId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 20, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA (field access) — never called by business code. */
    protected Reservation() {}

    /** {@code id} taken as a parameter, not auto-generated here: {@code
     * LedgerService} returns this same id to the caller in {@code
     * CheckAndReserveResult.Ok}. Generating it independently on both sides was a
     * real bug — the id returned to the caller never matched the actual database
     * row, so {@code releaseReservation(transferId, reservationId)} could never
     * find anything to release. */
    public Reservation(UUID id, UUID accountId, String transferId, BigDecimal amount, Instant expiresAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.transferId = Objects.requireNonNull(transferId, "transferId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.status = ReservationStatus.ACTIVE;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getTransferId() {
        return transferId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // No setStatus(): status transitions go through a conditional @Modifying query
    // (WHERE status = 'ACTIVE'), not mutate-and-save — otherwise the guard against a
    // double CONSUMED/RELEASED is lost. Same reasoning as Account.setBalance().
}
