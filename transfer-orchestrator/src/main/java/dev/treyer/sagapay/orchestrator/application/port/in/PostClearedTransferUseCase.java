package dev.treyer.sagapay.orchestrator.application.port.in;

import java.util.UUID;

/** No-op once the transfer has left {@code CLEARED}. */
public interface PostClearedTransferUseCase {

    void postCleared(UUID transferId);
}
