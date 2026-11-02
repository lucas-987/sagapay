package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.orchestrator.domain.Transfer;

import java.util.UUID;

public interface ConfirmTransferUseCase {

    /** Moves a BLOCKED transfer to CLEARED when the code is right and the window is open.
     * @throws dev.treyer.sagapay.orchestrator.domain.TransferNotFoundException unknown transfer
     * @throws dev.treyer.sagapay.orchestrator.domain.VerificationExpiredException the window closed
     * @throws dev.treyer.sagapay.orchestrator.domain.TransferNotBlockedException not blocked
     * @throws dev.treyer.sagapay.orchestrator.domain.InvalidVerificationCodeException wrong code */
    Transfer confirmTransfer(UUID transferId, String verificationCode);
}
