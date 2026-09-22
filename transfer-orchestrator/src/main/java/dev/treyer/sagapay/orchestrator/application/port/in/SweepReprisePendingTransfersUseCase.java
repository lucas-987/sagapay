package dev.treyer.sagapay.orchestrator.application.port.in;

/** Resumes any transfer stuck in {@code RESERVED} past the configured grace
 * period, driving each through {@link ContinueReservedTransferUseCase} -- the
 * crash-recovery half of the saga (§7): proves that persisting saga state has
 * a point, independent of whether the eager direct path ({@link
 * AdvanceSagaUseCase}) ever ran for that transfer. Same family as the ledger's
 * {@code ExpireReservationsUseCase} -- a periodic reconciler, not triggered by
 * anything in particular. */
public interface SweepReprisePendingTransfersUseCase {

    /** @return how many transfers were found stuck and resumed. */
    int sweepStuckReservedTransfers();
}
