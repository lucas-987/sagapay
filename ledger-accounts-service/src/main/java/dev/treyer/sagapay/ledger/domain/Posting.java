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

/** One line of the append-only, double-entry ledger. */
@Entity
@Table(name = "postings")
public class Posting {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "entry_group", nullable = false, updatable = false)
    private UUID entryGroup;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    // Not a foreign key: transfers live in another service's database.
    @Column(name = "transfer_id", nullable = false, updatable = false, length = 64)
    private String transferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "leg", nullable = false, updatable = false, length = 8)
    private PostingLeg leg;

    @Column(name = "amount", nullable = false, updatable = false, precision = 20, scale = 4)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Posting() {}

    public Posting(UUID entryGroup, UUID accountId, String transferId, PostingLeg leg, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.entryGroup = Objects.requireNonNull(entryGroup, "entryGroup");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.transferId = Objects.requireNonNull(transferId, "transferId");
        this.leg = Objects.requireNonNull(leg, "leg");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getEntryGroup() {
        return entryGroup;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getTransferId() {
        return transferId;
    }

    public PostingLeg getLeg() {
        return leg;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

}
