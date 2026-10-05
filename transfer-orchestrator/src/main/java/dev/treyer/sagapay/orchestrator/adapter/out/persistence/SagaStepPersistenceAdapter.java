package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.application.port.out.SagaStepPort;
import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
class SagaStepPersistenceAdapter implements SagaStepPort {

    private final SagaStepRepository repository;

    SagaStepPersistenceAdapter(SagaStepRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(SagaStep step) {
        repository.save(step);
    }

    @Override
    public List<SagaStep> findByTransferId(UUID transferId) {
        return repository.findByTransferIdOrderByAtAscIdAsc(transferId);
    }
}
