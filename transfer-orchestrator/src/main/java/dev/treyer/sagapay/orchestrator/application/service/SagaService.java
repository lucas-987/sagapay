package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.OutboxRepository;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.SagaStepRepository;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.TransferRepository;
import dev.treyer.sagapay.orchestrator.application.port.in.AdvanceSagaUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ContinueReservedTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.InitiateTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One class for every use case, rather than one class per use case, same
 * reasoning as the ledger's {@code LedgerService}: they share the same out ports
 * and operate on the same aggregate (a transfer and its saga history). */
@Service
public class SagaService implements InitiateTransferUseCase, AdvanceSagaUseCase, ContinueReservedTransferUseCase {

    private final TransferRepository transfers;
    private final SagaStepRepository sagaSteps;
    private final OutboxRepository outbox;
    private final LedgerPort ledger;
    private final JsonMapper jsonMapper;
    private final EntityManager entityManager;

    public SagaService(TransferRepository transfers, SagaStepRepository sagaSteps, OutboxRepository outbox,
                        LedgerPort ledger, JsonMapper jsonMapper, EntityManager entityManager) {
        this.transfers = transfers;
        this.sagaSteps = sagaSteps;
        this.outbox = outbox;
        this.ledger = ledger;
        this.jsonMapper = jsonMapper;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public Transfer initiateTransfer(UUID senderId, UUID senderAccountId, UUID recipientId, UUID recipientAccountId,
                                      Money amount, UUID idempotencyKey, String note) {
        UUID id = UUID.randomUUID();
        int inserted = transfers.insertIfAbsent(id, idempotencyKey, senderId, senderAccountId, recipientId,
                recipientAccountId, amount.amount(), amount.currency().getCurrencyCode(), note);

        Transfer transfer = transfers.findBySenderIdAndIdempotencyKey(senderId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "transfer row vanished for sender " + senderId + " / idempotency key " + idempotencyKey));

        if (inserted == 1) {
            appendOutbox(transfer, "TransferInitiated");
        }
        return transfer;
    }

    @Override
    @Transactional
    public void advance(UUID transferId) {
        Transfer transfer = requireTransfer(transferId);
        if (transfer.getStatus() != TransferStatus.INITIATED) {
            // Already advanced by a concurrent caller (the reprise poller, or a
            // replayed request) -- idempotent no-op, not an error.
            return;
        }

        ReservationResult result = ledger.checkAndReserve(transferId.toString(), transfer.getSenderAccountId(),
                Money.of(transfer.getAmount(), transfer.getCurrency()));

        switch (result) {
            case ReservationResult.Ok ok -> {
                int updated = transfers.transitionWithReservation(
                        transferId, TransferStatus.INITIATED, TransferStatus.RESERVED, ok.reservationId());
                if (updated == 0) {
                    return; // lost a race to a concurrent advance() -- its outcome stands
                }
                sagaSteps.save(new SagaStep(transferId, "RESERVE", "OK", null));
                appendOutbox(transfer, "FundsReserved");

                // Without this, continueFromReserved()'s own findById (same
                // transaction, same persistence context) would return this very
                // instance from the L1 cache -- still showing the pre-transition
                // status, since @Modifying queries write straight to the DB
                // without updating already-loaded entities.
                entityManager.detach(transfer);

                // No fraud branch in M2 -- reserved funds chain straight into posting,
                // in the same method rather than waiting for a separate trigger.
                continueFromReserved(transferId);
            }
            case ReservationResult.InsufficientFunds ignored -> {
                int updated = transfers.transitionToFailed(
                        transferId, TransferStatus.INITIATED, TransferStatus.FAILED, "INSUFFICIENT_FUNDS");
                if (updated == 0) {
                    return;
                }
                sagaSteps.save(new SagaStep(transferId, "RESERVE", "FAILED", null));
                appendOutbox(transfer, "TransferFailed");
            }
        }
    }

    @Override
    @Transactional
    public void continueFromReserved(UUID transferId) {
        Transfer transfer = requireTransfer(transferId);
        if (transfer.getStatus() != TransferStatus.RESERVED) {
            // Not reserved (yet), or already posted by a concurrent caller --
            // idempotent no-op. Lets the reprise poller call this blindly for
            // any transfer it finds stuck in RESERVED.
            return;
        }

        boolean posted = ledger.postTransfer(transferId.toString(), transfer.getSenderAccountId(),
                transfer.getRecipientAccountId(), Money.of(transfer.getAmount(), transfer.getCurrency()));
        if (!posted) {
            // The ledger never reports posted=false for a transfer it already
            // accepted a reservation for (see LedgerService.postTransfer) -- left
            // in RESERVED rather than guessed at, so the poller retries it.
            return;
        }

        int updated = transfers.transitionStatus(transferId, TransferStatus.RESERVED, TransferStatus.POSTED);
        if (updated == 0) {
            return;
        }
        sagaSteps.save(new SagaStep(transferId, "POST", "OK", null));
        appendOutbox(transfer, "TransferPosted");
    }

    private Transfer requireTransfer(UUID transferId) {
        return transfers.findById(transferId)
                .orElseThrow(() -> new IllegalArgumentException("unknown transfer " + transferId));
    }

    /** {@code transfer}'s immutable fields (sender/recipient/amount/currency)
     * don't change across the saga, so the originally-loaded entity is reused for
     * every event -- no re-fetch needed after a status transition written via
     * {@code @Modifying} query (those don't update the in-memory entity anyway). */
    private void appendOutbox(Transfer transfer, String eventType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transferId", transfer.getId().toString());
        payload.put("senderId", transfer.getSenderId().toString());
        payload.put("recipientId", transfer.getRecipientId().toString());
        payload.put("amount", transfer.getAmount().toPlainString());
        payload.put("currency", transfer.getCurrency());
        payload.put("eventType", eventType);
        outbox.save(new OutboxRow(UUID.randomUUID(), "Transfer", transfer.getId(), eventType,
                jsonMapper.writeValueAsString(payload), null));
    }
}
