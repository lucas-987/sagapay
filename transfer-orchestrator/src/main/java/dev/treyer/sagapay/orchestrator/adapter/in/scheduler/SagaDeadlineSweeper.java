package dev.treyer.sagapay.orchestrator.adapter.in.scheduler;

import dev.treyer.sagapay.orchestrator.application.port.in.SweepOverdueTransfersUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ends the transfers left in {@code SCREENING} or {@code BLOCKED} past their
 * deadline. Active in every profile: money stays held until it runs.
 */
@Component
public class SagaDeadlineSweeper {

    private final SweepOverdueTransfersUseCase sweepUseCase;

    public SagaDeadlineSweeper(SweepOverdueTransfersUseCase sweepUseCase) {
        this.sweepUseCase = sweepUseCase;
    }

    /** {@code fixedDelay}, so a slow sweep cannot overlap with the next one; the first
     * sweep waits one interval, so the application is ready before it ends anything. */
    @Scheduled(
            initialDelayString = "${saga.deadline.sweep-interval-ms:1000}",
            fixedDelayString = "${saga.deadline.sweep-interval-ms:1000}")
    public void sweep() {
        sweepUseCase.sweepOverdueTransfers();
    }
}
