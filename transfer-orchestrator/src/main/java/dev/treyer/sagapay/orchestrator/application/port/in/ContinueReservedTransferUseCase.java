package dev.treyer.sagapay.orchestrator.application.port.in;

import java.util.UUID;

/** No-op once the transfer has left {@code RESERVED}. */
public interface ContinueReservedTransferUseCase {

    void continueFromReserved(UUID transferId);
}
