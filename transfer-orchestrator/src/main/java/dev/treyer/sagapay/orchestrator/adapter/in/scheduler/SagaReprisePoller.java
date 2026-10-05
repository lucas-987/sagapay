package dev.treyer.sagapay.orchestrator.adapter.in.scheduler;

import dev.treyer.sagapay.orchestrator.application.port.in.SweepReprisePendingTransfersUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resumes transfers left in {@code INITIATED} or {@code RESERVED} by a crash, a
 * lost background task or an unavailable ledger, and releases the reservation of
 * {@code FAILED} transfers whose release is still pending. Active in every profile: a
 * stuck saga is a production concern.
 */
@Component
public class SagaReprisePoller {

    private static final Logger log = LoggerFactory.getLogger(SagaReprisePoller.class);

    private final SweepReprisePendingTransfersUseCase sweepUseCase;

    public SagaReprisePoller(SweepReprisePendingTransfersUseCase sweepUseCase) {
        this.sweepUseCase = sweepUseCase;
    }

    /** {@code fixedDelay}, so a slow sweep cannot overlap with the next one. */
    @Scheduled(fixedDelayString = "${saga.reprise.sweep-interval-ms:60000}")
    public void sweep() {
        int resumed = sweepUseCase.sweepStuckTransfers();
        if (resumed > 0) {
            log.info("Resumed {} stuck transfer(s)", resumed);
        }
    }
}
