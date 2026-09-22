package dev.treyer.sagapay.orchestrator.application.port.in;

import java.util.UUID;

/** The {@code RESERVED} -> {@code POSTED} continuation, factored out of {@code
 * AdvanceSagaUseCase} so the reprise poller (§7) can resume a transfer stuck in
 * {@code RESERVED} without re-running {@code checkAndReserve} — the same logic
 * {@code advance()} calls internally right after reserving, not a duplicate of
 * it. Idempotent no-op if the transfer has already left {@code RESERVED}. */
public interface ContinueReservedTransferUseCase {

    void continueFromReserved(UUID transferId);
}
