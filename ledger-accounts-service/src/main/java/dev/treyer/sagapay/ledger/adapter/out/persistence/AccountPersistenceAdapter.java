package dev.treyer.sagapay.ledger.adapter.out.persistence;

import dev.treyer.sagapay.ledger.application.port.out.AccountPort;
import dev.treyer.sagapay.ledger.domain.Account;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Component
class AccountPersistenceAdapter implements AccountPort {

    private final AccountRepository repository;

    AccountPersistenceAdapter(AccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Account> findByIdForUpdate(UUID id) {
        return repository.findByIdForUpdate(id);
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return repository.findById(id);
    }

    @Override
    public Optional<Account> findByHandle(String handle) {
        return repository.findByHandle(handle);
    }

    @Override
    public int debitIfSufficientFunds(UUID id, BigDecimal amount) {
        return repository.debitIfSufficientFunds(id, amount);
    }

    @Override
    public void credit(UUID id, BigDecimal amount) {
        repository.credit(id, amount);
    }

    @Override
    public Account create(Account account) {
        return repository.save(account);
    }
}
