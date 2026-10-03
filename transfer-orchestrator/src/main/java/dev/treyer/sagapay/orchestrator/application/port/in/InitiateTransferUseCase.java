package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.domain.Transfer;

import java.util.UUID;

public interface InitiateTransferUseCase {

    /** {@code created} is false on a replay: the transfer alone cannot tell 202
     * from 409. */
    record Result(Transfer transfer, boolean created) {}

    /** Idempotent on {@code (senderId, idempotencyKey)}. */
    Result initiateTransfer(
            UUID senderId,
            UUID senderAccountId,
            UUID recipientId,
            UUID recipientAccountId,
            Money amount,
            UUID idempotencyKey,
            String note);
}
