package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.Transfer;

import java.util.List;
import java.util.UUID;

public interface GetTransferUseCase {

    /** {@code steps} in write order (oldest first) -- what {@code
     * TransferDetail.steps} exposes over REST (§8). */
    record TransferWithSteps(Transfer transfer, List<SagaStep> steps) {}

    TransferWithSteps getTransfer(UUID transferId);
}
