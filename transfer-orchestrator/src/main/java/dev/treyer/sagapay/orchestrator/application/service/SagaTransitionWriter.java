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
 * The local-write half of each saga step — separated from {@link SagaService}
 * so each transition is its own short {@code @Transactional} method, called
 * only *after* the corresponding {@link
 * dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort} call has
 * already returned. A single {@code @Transactional} method spanning both the
 * gRPC call and these writes would hold a Postgres transaction (and its locks)
 * open for the duration of a network round trip to another service — the
 * opposite of what a saga's "many short local transactions" design is for.
 *
 * <p>A separate Spring bean, not a private method on {@code SagaService}: Spring's
 * transactional proxy only intercepts calls that cross a real bean boundary --
 * {@code this.someTransactionalMethod()} from within the same class silently
 * skips {@code @Transactional} entirely (self-invocation).
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

    /** @return false if a concurrent caller already moved the transfer out of
     * {@code INITIATED} — its outcome stands, nothing more to write here. */
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

    /** The ledger definitively refused {@code step} for this transfer. The
     * ledger's own answer goes into {@code saga_steps.detail}: {@code
     * failureReason} only says which step was refused, the detail says why. */
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
