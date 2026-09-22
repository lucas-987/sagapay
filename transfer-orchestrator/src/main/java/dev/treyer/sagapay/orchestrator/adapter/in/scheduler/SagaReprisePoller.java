package dev.treyer.sagapay.orchestrator.adapter.in.scheduler;

import dev.treyer.sagapay.orchestrator.application.port.in.SweepReprisePendingTransfersUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Adapter in — triggers {@link SweepReprisePendingTransfersUseCase} on a clock,
 * not an RPC/endpoint. The crash-recovery half of the {@code RESERVED} ->
 * {@code POSTED} chain: proves a saga can resume without the eager direct call
 * (see {@code SagaReprisePollerTest}, which forces a transfer into {@code
 * RESERVED} without ever going through {@code advance()}). Same family as the
 * ledger's {@code ReservationExpirySweeper} — a reconciler, active in every
 * profile.
 */
@Component
public class SagaReprisePoller {

    private static final Logger log = LoggerFactory.getLogger(SagaReprisePoller.class);

    private final SweepReprisePendingTransfersUseCase sweepUseCase;

    public SagaReprisePoller(SweepReprisePendingTransfersUseCase sweepUseCase) {
        this.sweepUseCase = sweepUseCase;
    }

    /** {@code fixedDelay}, not {@code fixedRate}: the next run starts from the
     * end of the previous one, so a slow sweep can't overlap with itself. */
    @Scheduled(fixedDelayString = "${saga.reprise.sweep-interval-ms:60000}")
    public void sweep() {
        int resumed = sweepUseCase.sweepStuckReservedTransfers();
        if (resumed > 0) {
            log.info("Resumed {} transfer(s) stuck in RESERVED", resumed);
        }
    }
}
