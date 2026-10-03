package dev.treyer.sagapay.orchestrator.application.port.in;

import java.util.UUID;

/** No-op once the transfer has left {@code INITIATED}, so the eager path and the
 * reprise poller can both call it. */
public interface AdvanceSagaUseCase {

    void advance(UUID transferId);
}
