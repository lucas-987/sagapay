package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.application.port.out.OutboxPort;
import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import org.springframework.stereotype.Component;

@Component
class OutboxPersistenceAdapter implements OutboxPort {

    private final OutboxRepository repository;

    OutboxPersistenceAdapter(OutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(OutboxRow row) {
        repository.save(row);
    }
}
