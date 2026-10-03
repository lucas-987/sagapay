package dev.treyer.sagapay.orchestrator.application.port.in;

import java.util.UUID;

/** The eager, synchronous continuation of a saga right after {@code
 * initiateTransfer} — the "instant demo" path. Idempotent no-op if the transfer
 * has already left {@code INITIATED} (e.g. the reprise poller got there first) --
 * which is why the poller can also call it, for transfers the eager path never
 * moved out of {@code INITIATED}. */
public interface AdvanceSagaUseCase {

    void advance(UUID transferId);
}
