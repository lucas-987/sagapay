package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.domain.Transfer;

import java.util.UUID;

public interface InitiateTransferUseCase {

    /** {@code created} is false on a repeat: only a created transfer starts its saga. */
    record Result(Transfer transfer, boolean created) {}

    /** Idempotent on {@code (senderId, idempotencyKey)}.
     *
     * @throws dev.treyer.sagapay.orchestrator.domain.IdempotencyKeyReusedException when the key
     *         belongs to a transfer of a different request
     */
    Result initiateTransfer(
            UUID senderId,
            UUID senderAccountId,
            UUID recipientId,
            UUID recipientAccountId,
            Money amount,
            UUID idempotencyKey,
            String note);
}
