package dev.treyer.sagapay.ledger.adapter.out.persistence;

import dev.treyer.sagapay.ledger.application.port.out.LedgerIdempotencyPort;
import dev.treyer.sagapay.ledger.domain.LedgerIdempotency;
import dev.treyer.sagapay.ledger.domain.LedgerIdempotencyId;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class LedgerIdempotencyPersistenceAdapter implements LedgerIdempotencyPort {

    private final LedgerIdempotencyRepository repository;

    LedgerIdempotencyPersistenceAdapter(LedgerIdempotencyRepository repository) {
        this.repository = repository;
    }

    @Override
    public int insertIfAbsent(String transferId, String operation, String resultJson) {
        return repository.insertIfAbsent(transferId, operation, resultJson);
    }

    @Override
    public Optional<LedgerIdempotency> findById(LedgerIdempotencyId id) {
        return repository.findById(id);
    }
}
