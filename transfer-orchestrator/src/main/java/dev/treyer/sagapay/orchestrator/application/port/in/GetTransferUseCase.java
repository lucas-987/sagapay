package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.Transfer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A transfer the user is not a party to reads as unknown: a third party cannot tell it exists. */
public interface GetTransferUseCase {

    /**
     * {@code steps} oldest first, and only for the sender: see {@code SenderOnlyDetails}.
     * {@code confirmationDeadline} is the sender's while the transfer is blocked, else null.
     */
    record TransferWithSteps(Transfer transfer, List<SagaStep> steps, Instant confirmationDeadline) {}

    TransferWithSteps getTransfer(UUID transferId, UUID userId);
}
