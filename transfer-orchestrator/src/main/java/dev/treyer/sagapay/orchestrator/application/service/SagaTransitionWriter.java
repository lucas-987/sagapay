package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.adapter.out.persistence.OutboxRepository;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.SagaStepRepository;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.TransferRepository;
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

    private final TransferRepository transfers;
    private final SagaStepRepository sagaSteps;
    private final OutboxRepository outbox;
    private final JsonMapper jsonMapper;

    SagaTransitionWriter(TransferRepository transfers, SagaStepRepository sagaSteps, OutboxRepository outbox,
                          JsonMapper jsonMapper) {
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
    boolean applyRejected(UUID transferId, Transfer transfer, TransferStatus fromStatus, String step,
                          String failureReason, LedgerRejectedException rejection) {
        int updated = transfers.transitionToFailed(transferId, fromStatus, TransferStatus.FAILED, failureReason);
        if (updated == 0) {
            return false;
        }
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("ledgerStatus", rejection.ledgerStatus());
        detail.put("message", rejection.getMessage());
        sagaSteps.save(new SagaStep(transferId, step, "FAILED", jsonMapper.writeValueAsString(detail)));
        outbox.save(OutboxEvents.forFailedTransfer(jsonMapper, transfer, failureReason));
        return true;
    }
}
