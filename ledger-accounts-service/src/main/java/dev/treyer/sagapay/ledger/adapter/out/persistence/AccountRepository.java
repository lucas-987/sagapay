package dev.treyer.sagapay.ledger.adapter.out.persistence;

import dev.treyer.sagapay.ledger.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /** Serializes concurrent debits on the same account so two transfers can't both
     * read the balance before either writes it back. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);

    /** Returns {@code Optional}, not {@code List}: {@code handle} is {@code UNIQUE}. */
    Optional<Account> findByHandle(String handle);

    /** Returns the affected-row count: 0 means insufficient balance, letting the
     * caller detect it from the count instead of relying on an exception. */
    @Modifying
    @Query("update Account a set a.balance = a.balance - :amount "
            + "where a.id = :id and a.balance - :amount >= 0")
    int debitIfSufficientFunds(@Param("id") UUID id, @Param("amount") BigDecimal amount);

    @Modifying
    @Query("update Account a set a.balance = a.balance + :amount where a.id = :id")
    void credit(@Param("id") UUID id, @Param("amount") BigDecimal amount);
}
