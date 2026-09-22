package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SagaStepRepository extends JpaRepository<SagaStep, Long> {

    List<SagaStep> findByTransferIdOrderByAtAsc(UUID transferId);
}
