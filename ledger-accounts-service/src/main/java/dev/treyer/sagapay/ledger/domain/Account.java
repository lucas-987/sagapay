package dev.treyer.sagapay.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "handle", nullable = false, unique = true, length = 64)
    private String handle;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 20, scale = 4)
    private BigDecimal balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {}

    public Account(String handle, String displayName, String currency, BigDecimal balance) {
        this.id = UUID.randomUUID();
        this.handle = Objects.requireNonNull(handle, "handle");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.balance = Objects.requireNonNull(balance, "balance");
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getHandle() {
        return handle;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // No setBalance(): the balance only changes through the conditional update that
    // guards against overdraft.
}
