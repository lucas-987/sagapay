package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.application.port.out.OutboxPort;
import dev.treyer.sagapay.orchestrator.application.port.out.SagaStepPort;
import dev.treyer.sagapay.orchestrator.application.port.out.TransferPort;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

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

    private final TransferPort transfers;
    private final SagaStepPort sagaSteps;
    private final OutboxPort outbox;
    private final JsonMapper jsonMapper;

    SagaTransitionWriter(TransferPort transfers, SagaStepPort sagaSteps, OutboxPort outbox, JsonMapper jsonMapper) {
        this.transfers = transfers;
        this.sagaSteps = sagaSteps;
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
    }

    /** @return false when a concurrent caller already moved the transfer on. */
    @Transactional
    boolean applyReserved(UUID transferId, Transfer transfer, UUID reservationId) {
        int updated = transfers.transitionWithReservation(
                transferId, TransferStatus.INITIATED, TransferStatus.RESERVED, reservationId);
        if (updated == 0) {
            return false;
        }
        sagaSteps.save(new SagaStep(transferId, "RESERVE", "OK", null));
        outbox.save(OutboxEvents.forTransfer(jsonMapper, transfer, "FundsReserved"));
        return true;
    }

    @Transactional
    boolean applyPosted(UUID transferId, Transfer transfer) {
        int updated = transfers.transitionStatus(transferId, TransferStatus.RESERVED, TransferStatus.POSTED);
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
