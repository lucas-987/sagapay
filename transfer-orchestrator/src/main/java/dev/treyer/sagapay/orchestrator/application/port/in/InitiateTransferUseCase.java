package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.domain.Transfer;

import java.util.UUID;

public interface InitiateTransferUseCase {

    /** {@code created}: false on a replay (existing transfer returned as-is) --
     * the REST adapter (§8) needs this to answer 202 vs 409, a distinction the
     * returned {@link Transfer} alone can't make (its shape is identical either
     * way). */
    record Result(Transfer transfer, boolean created) {}

    /** Idempotent on {@code (senderId, idempotencyKey)}: a replay returns the
     * existing transfer rather than creating a second one. */
    Result initiateTransfer(UUID senderId, UUID senderAccountId, UUID recipientId, UUID recipientAccountId,
                             Money amount, UUID idempotencyKey, String note);
}
