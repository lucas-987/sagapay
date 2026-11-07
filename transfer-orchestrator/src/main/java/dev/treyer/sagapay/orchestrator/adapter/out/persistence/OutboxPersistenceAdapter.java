package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.application.port.out.OutboxPort;
import dev.treyer.sagapay.orchestrator.application.port.out.OutboxRelayPort;
import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
class OutboxPersistenceAdapter implements OutboxPort, OutboxRelayPort {

    private final OutboxRepository repository;

    OutboxPersistenceAdapter(OutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(OutboxRow row) {
        repository.save(row);
    }

    @Override
    public List<OutboxRow> findUnpublishedForUpdateSkipLocked(int limit) {
        return repository.findUnpublishedForUpdateSkipLocked(limit);
    }

    @Override
    public void markPublished(UUID id) {
        repository.markPublished(id);
    }
}
