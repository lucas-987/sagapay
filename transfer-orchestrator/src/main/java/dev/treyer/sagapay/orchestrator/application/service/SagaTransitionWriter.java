package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.application.port.out.OutboxPort;
import dev.treyer.sagapay.orchestrator.application.port.out.ProcessedEventPort;
import dev.treyer.sagapay.orchestrator.application.port.out.SagaStepPort;
import dev.treyer.sagapay.orchestrator.application.port.out.TransferPort;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

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

    private static final String RELEASE_STEP = "RELEASE";
    static final String SCREEN_STEP = "SCREEN";
    private static final String FRAUD_CONSUMER = "orchestrator-fraud";

    private final TransferPort transfers;
    private final SagaStepPort sagaSteps;
    private final OutboxPort outbox;
    private final ProcessedEventPort processedEvents;
    private final JsonMapper jsonMapper;
    private final long screeningTimeoutMs;
    private final long confirmationWindowMs;

    SagaTransitionWriter(
            TransferPort transfers,
            SagaStepPort sagaSteps,
            OutboxPort outbox,
            ProcessedEventPort processedEvents,
            JsonMapper jsonMapper,
            @Value("${saga.screening.timeout-ms:10000}") long screeningTimeoutMs,
            @Value("${saga.confirmation.window-ms:120000}") long confirmationWindowMs) {
        this.transfers = transfers;
        this.sagaSteps = sagaSteps;
        this.outbox = outbox;
        this.processedEvents = processedEvents;
        this.jsonMapper = jsonMapper;
        this.screeningTimeoutMs = screeningTimeoutMs;
        this.confirmationWindowMs = confirmationWindowMs;
    }

    /** @return false when a concurrent caller already moved the transfer on. */
    @Transactional
    boolean applyReserved(UUID transferId, Transfer transfer, UUID reservationId) {
        int updated = transfers.transitionWithReservation(
                transferId, TransferStatus.INITIATED, TransferStatus.SCREENING, reservationId);
        if (updated == 0) {
            return false;
        }
        sagaSteps.save(new SagaStep(transferId, "RESERVE", "OK", null));
        outbox.save(OutboxEvents.forTransfer(jsonMapper, transfer, "FundsReserved"));
        sagaSteps.save(new SagaStep(
                transferId, SCREEN_STEP, "RETRY", null, Instant.now().plusMillis(screeningTimeoutMs)));
        return true;
    }

    /** The event is recorded even when the transfer is not being screened, whatever the reason.
     * @return true when this call moved the transfer to {@code CLEARED}. */
    @Transactional
    boolean applyCleared(FraudResult result) {
        if (!processedEvents.markProcessed(result.eventId(), FRAUD_CONSUMER)) {
            return false;
        }
        int updated = transfers.transitionToCleared(result.transferId(), result.score(), result.reasons());
        if (updated == 0) {
            return false;
        }
        sagaSteps.save(new SagaStep(result.transferId(), SCREEN_STEP, "OK", fraudDetail(result)));
        return true;
    }

    /** Same rule as {@link #applyCleared}; the reservation stays held, so the ledger is not called.
     * @return true when this call moved the transfer to {@code BLOCKED}. */
    @Transactional
    boolean applyFlagged(FraudResult result) {
        if (!processedEvents.markProcessed(result.eventId(), FRAUD_CONSUMER)) {
            return false;
        }
        int updated = transfers.transitionToBlocked(result.transferId(), result.score(), result.reasons());
        if (updated == 0) {
            return false;
        }
        sagaSteps.save(new SagaStep(
                result.transferId(),
                SCREEN_STEP,
                "RETRY",
                fraudDetail(result),
                Instant.now().plusMillis(confirmationWindowMs)));
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
    boolean applyPosted(UUID transferId, Transfer transfer) {
        int updated = transfers.transitionStatus(transferId, TransferStatus.CLEARED, TransferStatus.POSTED);
        if (updated == 0) {
            return false;
        }
        sagaSteps.save(new SagaStep(transferId, "POST", "OK", null));
        outbox.save(OutboxEvents.forTransfer(jsonMapper, transfer, "TransferPosted"));
        return true;
    }

    @Transactional
    boolean applyInsufficientFunds(UUID transferId, Transfer transfer) {
        String failureReason = "INSUFFICIENT_FUNDS";
        int updated = transfers.transitionToFailed(
                transferId, TransferStatus.INITIATED, TransferStatus.FAILED, failureReason);
        if (updated == 0) {
            return false;
        }
        sagaSteps.save(new SagaStep(transferId, "RESERVE", "FAILED", null));
        outbox.save(OutboxEvents.forFailedTransfer(jsonMapper, transfer, failureReason));
        return true;
    }

    /** {@code failureReason} names the refused step; the ledger's answer goes into
     * the step detail. */
    @Transactional
    boolean applyRejected(
            UUID transferId,
            Transfer transfer,
            TransferStatus fromStatus,
            String step,
            String failureReason,
            LedgerRejectedException rejection) {
        return failOnRejection(transferId, transfer, fromStatus, step, failureReason, rejection);
    }

    /** Same as {@link #applyRejected}, plus the pending release of the reservation
     * the transfer holds, so a crash after this transaction cannot lose it. */
    @Transactional
    boolean applyRejectedAndRelease(
            UUID transferId,
            Transfer transfer,
            TransferStatus fromStatus,
            String step,
            String failureReason,
            LedgerRejectedException rejection) {
        if (!failOnRejection(transferId, transfer, fromStatus, step, failureReason, rejection)) {
            return false;
        }
        sagaSteps.save(new SagaStep(transferId, RELEASE_STEP, "RETRY", null));
        return true;
    }

    @Transactional
    void recordReleased(UUID transferId) {
        sagaSteps.save(new SagaStep(transferId, RELEASE_STEP, "COMPENSATED", null));
    }

    @Transactional
    void recordReleaseRefused(UUID transferId, LedgerRejectedException rejection) {
        sagaSteps.save(new SagaStep(transferId, RELEASE_STEP, "FAILED", rejectionDetail(rejection)));
    }

    private boolean failOnRejection(
            UUID transferId,
            Transfer transfer,
            TransferStatus fromStatus,
            String step,
            String failureReason,
            LedgerRejectedException rejection) {
        int updated = transfers.transitionToFailed(transferId, fromStatus, TransferStatus.FAILED, failureReason);
        if (updated == 0) {
            return false;
        }
        sagaSteps.save(new SagaStep(transferId, step, "FAILED", rejectionDetail(rejection)));
        outbox.save(OutboxEvents.forFailedTransfer(jsonMapper, transfer, failureReason));
        return true;
    }

    private String rejectionDetail(LedgerRejectedException rejection) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("ledgerStatus", rejection.ledgerStatus());
        detail.put("message", rejection.getMessage());
        return jsonMapper.writeValueAsString(detail);
    }
}
