package dev.treyer.sagapay.orchestrator.application.port.in;

/** Ends the transfers whose screening deadline or confirmation window has passed,
 * and releases their reservation. */
public interface SweepOverdueTransfersUseCase {

    /** @return how many transfers were failed before the sweep ended (it stops
     * early if the ledger is unavailable). */
    int sweepOverdueTransfers();
}
