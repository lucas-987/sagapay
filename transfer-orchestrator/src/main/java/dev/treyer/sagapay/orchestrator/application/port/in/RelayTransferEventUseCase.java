package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.orchestrator.domain.TransferEvent;

/** Pushes a published transfer event to the open streams of the users it concerns. */
public interface RelayTransferEventUseCase {

    void relay(TransferEvent event);
}
