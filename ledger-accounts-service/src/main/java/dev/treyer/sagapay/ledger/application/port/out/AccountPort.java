package dev.treyer.sagapay.ledger.application.port.out;

import dev.treyer.sagapay.ledger.domain.Account;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface AccountPort {

    Optional<Account> findByIdForUpdate(UUID id);

    Optional<Account> findById(UUID id);

    /** {@code handle} is UNIQUE in the database — at most one match. */
    Optional<Account> findByHandle(String handle);

    /** @return number of rows affected: 0 = insufficient balance. */
    int debitIfSufficientFunds(UUID id, BigDecimal amount);

    void credit(UUID id, BigDecimal amount);

    /** Only caller today is the demo seed ({@code local} profile) — no business use
     * case creates an account (no {@code CreateAccount} RPC exists), so there's no
     * matching in-port; the seed reaches this out-port directly, like a test would. */
    Account create(Account account);
}
