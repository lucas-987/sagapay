package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.Transfer;

import java.util.List;
import java.util.UUID;

/** A transfer the user is not a party to reads as unknown: a third party cannot tell it exists. */
public interface GetTransferUseCase {

    /** {@code steps} oldest first. */
    record TransferWithSteps(Transfer transfer, List<SagaStep> steps) {}

    TransferWithSteps getTransfer(UUID transferId, UUID userId);
}
