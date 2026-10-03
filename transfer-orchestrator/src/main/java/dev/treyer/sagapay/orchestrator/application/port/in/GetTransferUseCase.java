package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.Transfer;

import java.util.List;
import java.util.UUID;

public interface GetTransferUseCase {

    /** {@code steps} oldest first. */
    record TransferWithSteps(Transfer transfer, List<SagaStep> steps) {}

    TransferWithSteps getTransfer(UUID transferId);
}
