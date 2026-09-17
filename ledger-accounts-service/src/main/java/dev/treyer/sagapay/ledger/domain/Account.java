package dev.treyer.sagapay.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A class, not a record: JPA requires a no-arg constructor plus fields mutable by
 * reflection — two constraints incompatible with a record. */
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

    /**
     * Required by JPA (field access, see the annotations on the fields above) —
     * never called by business code, only Hibernate uses it to reconstruct the
     * entity from a row read from the database, then fills the fields by
     * reflection. {@code protected}: the spec allows public/protected, protected
     * discourages calling it directly from the rest of the code.
     */
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

    // No setBalance(): the balance only ever changes through a conditional
    // @Modifying query (UPDATE ... WHERE balance - :amt >= 0), never load/mutate/save
    // — a public setter would invite bypassing that atomic guard.
}
