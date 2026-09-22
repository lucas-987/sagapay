package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.domain.Transfer;

import java.util.UUID;

public interface InitiateTransferUseCase {

    /** Idempotent on {@code (senderId, idempotencyKey)}: a replay returns the
     * existing transfer rather than creating a second one. */
    Transfer initiateTransfer(UUID senderId, UUID senderAccountId, UUID recipientId, UUID recipientAccountId,
                               Money amount, UUID idempotencyKey, String note);
}
