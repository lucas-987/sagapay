package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.application.port.out.OutboxPort;
import dev.treyer.sagapay.orchestrator.application.port.out.OutboxRelayPort;
import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
class OutboxPersistenceAdapter implements OutboxPort, OutboxRelayPort {

    // Each service has its own database, so this only has to differ from its other advisory locks.
    private static final long PUBLISHER_LOCK = 1;

    private final OutboxRepository repository;

    OutboxPersistenceAdapter(OutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(OutboxRow row) {
        repository.save(row);
    }

    @Override
    public boolean tryLockPublisher() {
        return repository.tryAdvisoryTransactionLock(PUBLISHER_LOCK);
    }

    @Override
    public List<OutboxRow> findPublishable(int limit) {
        return repository.findPublishable(limit);
    }

    @Override
    public void markPublished(UUID id) {
        repository.markPublished(id);
    }

    @Override
    public void quarantine(String partitionKey, UUID outboxId, String reason) {
        repository.quarantine(partitionKey, outboxId, reason);
    }

    @Override
    public double oldestPublishableAgeSeconds() {
        return repository.oldestPublishableAgeSeconds();
    }

    @Override
    public long countQuarantinedKeys() {
        return repository.countQuarantinedKeys();
    }
}
