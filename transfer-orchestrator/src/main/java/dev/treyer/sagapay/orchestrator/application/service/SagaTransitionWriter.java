package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.common.event.TransferEventType;
import dev.treyer.sagapay.orchestrator.application.port.out.OutboxPort;
import dev.treyer.sagapay.orchestrator.application.port.out.ProcessedEventPort;
import dev.treyer.sagapay.orchestrator.application.port.out.SagaStepPort;
import dev.treyer.sagapay.orchestrator.application.port.out.TransferPort;
import dev.treyer.sagapay.orchestrator.domain.FailureReason;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.SagaStepName;
import dev.treyer.sagapay.orchestrator.domain.SagaStepOutcome;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Each transition is a short transaction run after the ledger call returned, so no
 * transaction spans a network round trip. A separate bean: {@code @Transactional}
 * is ignored on calls within the same class.
 */
@Component
class SagaTransitionWriter {

    private static final String FRAUD_CONSUMER = "orchestrator-fraud";

    private final TransferPort transfers;
    private final SagaStepPort sagaSteps;
    private final OutboxPort outbox;
    private final ProcessedEventPort processedEvents;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final long screeningTimeoutMs;
    private final long confirmationWindowMs;

    SagaTransitionWriter(
            TransferPort transfers,
            SagaStepPort sagaSteps,
            OutboxPort outbox,
            ProcessedEventPort processedEvents,
            JsonMapper jsonMapper,
            Clock clock,
            SagaSettings settings) {
        this.transfers = transfers;
        this.sagaSteps = sagaSteps;
        this.outbox = outbox;
        this.processedEvents = processedEvents;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.screeningTimeoutMs = settings.screeningTimeoutMs();
        this.confirmationWindowMs = settings.confirmationWindowMs();
    }

    /** @return false when a concurrent caller already moved the transfer on. */
    @Transactional
    boolean tryApplyReserved(UUID transferId, Transfer transfer, UUID reservationId) {
        int updated = transfers.transitionWithReservation(
                transferId, TransferStatus.INITIATED, TransferStatus.SCREENING, reservationId);
        if (updated == 0) {
            return false;
        }
        Instant now = clock.instant();
        sagaSteps.save(new SagaStep(transferId, SagaStepName.RESERVE, SagaStepOutcome.OK, null, now));
        outbox.save(OutboxEvents.forTransfer(jsonMapper, transfer, TransferEventType.FUNDS_RESERVED, now));
        sagaSteps.save(new SagaStep(
                transferId,
                SagaStepName.SCREEN,
                SagaStepOutcome.RETRY,
                null,
                clock.instant().plusMillis(screeningTimeoutMs),
                clock.instant()));
        return true;
    }

    /** The event is recorded even when the transfer is not being screened, whatever the reason.
     * @return true when this call moved the transfer to {@code CLEARED}. */
    @Transactional
    boolean tryApplyCleared(FraudResult result) {
        if (!processedEvents.tryMarkProcessed(result.eventId(), FRAUD_CONSUMER)) {
            return false;
        }
        int updated = transfers.transitionToCleared(result.transferId(), result.score(), result.reasons());
        if (updated == 0) {
            return false;
        }
        sagaSteps.save(new SagaStep(
                result.transferId(), SagaStepName.SCREEN, SagaStepOutcome.OK, fraudDetail(result), clock.instant()));
        return true;
    }

    /** Same rule as {@link #tryApplyCleared}; the reservation stays held, so the ledger is not called.
     * @return true when this call moved the transfer to {@code BLOCKED}. */
    @Transactional
    boolean tryApplyFlagged(FraudResult result) {
        if (!processedEvents.tryMarkProcessed(result.eventId(), FRAUD_CONSUMER)) {
            return false;
        }
        int updated = transfers.transitionToBlocked(result.transferId(), result.score(), result.reasons());
        if (updated == 0) {
            return false;
        }
        sagaSteps.save(new SagaStep(
                result.transferId(),
                SagaStepName.SCREEN,
                SagaStepOutcome.RETRY,
                fraudDetail(result),
                clock.instant().plusMillis(confirmationWindowMs),
                clock.instant()));
        return true;
    }

    /** The sender's verification closes the open SCREEN step, so the transfer is cleared on its
     * existing reservation and the ledger is not called here.
     * @return true when this call moved the transfer to {@code CLEARED}. */
    @Transactional
    boolean tryApplyConfirmed(UUID transferId, Instant now) {
        int updated = transfers.transitionBlockedToCleared(transferId, now);
        if (updated == 0) {
            return false;
        }
        sagaSteps.save(new SagaStep(
                transferId,
                SagaStepName.SCREEN,
                SagaStepOutcome.OK,
                "{\"verification\":\"SENDER_CONFIRMED\"}",
                clock.instant()));
        return true;
    }

    private String fraudDetail(FraudResult result) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("decision", result.decision().name());
        detail.put("score", result.score());
        detail.put("reasons", result.reasons());
        return jsonMapper.writeValueAsString(detail);
    }

    @Transactional
    boolean tryApplyPosted(UUID transferId, Transfer transfer) {
        int updated = transfers.transitionStatus(transferId, TransferStatus.CLEARED, TransferStatus.POSTED);
        if (updated == 0) {
            return false;
        }
        sagaSteps.save(new SagaStep(transferId, SagaStepName.POST, SagaStepOutcome.OK, null, clock.instant()));
        outbox.save(OutboxEvents.forTransfer(jsonMapper, transfer, TransferEventType.TRANSFER_POSTED, clock.instant()));
        return true;
    }

    @Transactional
    boolean tryApplyInsufficientFunds(UUID transferId, Transfer transfer) {
        FailureReason failureReason = FailureReason.INSUFFICIENT_FUNDS;
        int updated = transfers.transitionToFailed(
                transferId, TransferStatus.INITIATED, TransferStatus.FAILED, failureReason);
        if (updated == 0) {
            return false;
        }
        sagaSteps.save(new SagaStep(transferId, SagaStepName.RESERVE, SagaStepOutcome.FAILED, null, clock.instant()));
        outbox.save(OutboxEvents.forFailedTransfer(jsonMapper, transfer, failureReason, clock.instant()));
        return true;
    }

    /** {@code failureReason} names the refused step; the ledger's answer goes into
     * the step detail. */
    @Transactional
    boolean tryApplyRejected(
            UUID transferId,
            Transfer transfer,
            TransferStatus fromStatus,
            SagaStepName step,
            FailureReason failureReason,
            LedgerRejectedException rejection) {
        return tryFailOnRejection(transferId, transfer, fromStatus, step, failureReason, rejection);
    }

    /** Same as {@link #tryApplyRejected}, plus the pending release of the reservation
     * the transfer holds, so a crash after this transaction cannot lose it. */
    @Transactional
    boolean tryApplyRejectedAndRelease(
            UUID transferId,
            Transfer transfer,
            TransferStatus fromStatus,
            SagaStepName step,
            FailureReason failureReason,
            LedgerRejectedException rejection) {
        if (!tryFailOnRejection(transferId, transfer, fromStatus, step, failureReason, rejection)) {
            return false;
        }
        sagaSteps.save(new SagaStep(transferId, SagaStepName.RELEASE, SagaStepOutcome.RETRY, null, clock.instant()));
        return true;
    }

    /** The transfer holds a reservation whatever the waiting status it leaves.
     * @return false when a concurrent caller already moved the transfer on. */
    @Transactional
    boolean tryApplyTimedOut(
            UUID transferId, Transfer transfer, TransferStatus fromStatus, FailureReason failureReason) {
        if (!tryFail(transferId, transfer, fromStatus, failureReason)) {
            return false;
        }
        sagaSteps.save(new SagaStep(transferId, SagaStepName.RELEASE, SagaStepOutcome.RETRY, null, clock.instant()));
        return true;
    }

    @Transactional
    void recordReleased(UUID transferId) {
        sagaSteps.save(
                new SagaStep(transferId, SagaStepName.RELEASE, SagaStepOutcome.COMPENSATED, null, clock.instant()));
    }

    @Transactional
    void recordReleaseRefused(UUID transferId, LedgerRejectedException rejection) {
        sagaSteps.save(new SagaStep(
                transferId, SagaStepName.RELEASE, SagaStepOutcome.FAILED, rejectionDetail(rejection), clock.instant()));
    }

    private boolean tryFailOnRejection(
            UUID transferId,
            Transfer transfer,
            TransferStatus fromStatus,
            SagaStepName step,
            FailureReason failureReason,
            LedgerRejectedException rejection) {
        if (!tryFail(transferId, transfer, fromStatus, failureReason)) {
            return false;
        }
        sagaSteps.save(
                new SagaStep(transferId, step, SagaStepOutcome.FAILED, rejectionDetail(rejection), clock.instant()));
        return true;
    }

    /** The compare-and-set to {@code FAILED} and its {@code TransferFailed} event.
     * @return false when a concurrent caller already moved the transfer on. */
    private boolean tryFail(
            UUID transferId, Transfer transfer, TransferStatus fromStatus, FailureReason failureReason) {
        int updated = transfers.transitionToFailed(transferId, fromStatus, TransferStatus.FAILED, failureReason);
        if (updated == 0) {
            return false;
        }
        outbox.save(OutboxEvents.forFailedTransfer(jsonMapper, transfer, failureReason, clock.instant()));
        return true;
    }

    private String rejectionDetail(LedgerRejectedException rejection) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("ledgerStatus", rejection.ledgerStatus());
        detail.put("message", rejection.getMessage());
        return jsonMapper.writeValueAsString(detail);
    }
}
