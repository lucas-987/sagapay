package dev.treyer.sagapay.ledger.application.port.out;

import dev.treyer.sagapay.ledger.domain.Account;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface AccountPort {

    Optional<Account> findByIdForUpdate(UUID id);

    Optional<Account> findById(UUID id);

    Optional<Account> findByHandle(String handle);

    /** @return 0 when the balance is insufficient. */
    int debitIfSufficientFunds(UUID id, BigDecimal amount);

    void credit(UUID id, BigDecimal amount);

    /** No use case creates accounts: only the local seed calls this. */
    Account create(Account account);
}
