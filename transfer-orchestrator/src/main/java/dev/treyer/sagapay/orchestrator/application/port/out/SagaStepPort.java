package dev.treyer.sagapay.orchestrator.application.port.out;

import dev.treyer.sagapay.orchestrator.domain.SagaStep;

import java.util.List;
import java.util.UUID;

public interface SagaStepPort {

    void save(SagaStep step);

    /** Oldest first. */
    List<SagaStep> findByTransferId(UUID transferId);
}
