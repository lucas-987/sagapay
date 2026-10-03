package dev.treyer.sagapay.orchestrator.application.port.in;

/** Resumes transfers stuck in {@code INITIATED} or {@code RESERVED} past the
 * grace period, through the same code as the eager path. */
public interface SweepReprisePendingTransfersUseCase {

    /** @return how many stuck transfers were resumed before the sweep ended
     * (it stops early if the ledger is unavailable). */
    int sweepStuckTransfers();
}
