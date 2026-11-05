package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.orchestrator.domain.FraudDecision;
import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.Transfer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A transfer the user is not a party to reads as unknown: its existence is not revealed to a third party. */
public interface GetTransferUseCase {

    /** {@code steps} oldest first; {@code fraud} is null until the transfer has been screened. */
    record TransferWithSteps(Transfer transfer, List<SagaStep> steps, ScreeningVerdict fraud) {}

    record ScreeningVerdict(FraudDecision decision, BigDecimal score, List<String> reasons) {}

    TransferWithSteps getTransfer(UUID transferId, UUID userId);
}
