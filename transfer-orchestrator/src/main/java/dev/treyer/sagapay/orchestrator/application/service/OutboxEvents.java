package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Builds an {@link OutboxRow} for a saga transition — shared by {@link
 * SagaService} (the {@code TransferInitiated} event) and {@link
 * SagaTransitionWriter} (every later event), so the payload shape isn't
 * duplicated between the two. */
final class OutboxEvents {

    private OutboxEvents() {}

    /** {@code transfer}'s sender/recipient/amount/currency don't change across
     * the saga, so the originally-loaded entity is reused for every event, no
     * matter which step is being recorded. */
    static OutboxRow forTransfer(JsonMapper jsonMapper, Transfer transfer, String eventType) {
        return forTransfer(jsonMapper, transfer, eventType, null);
    }

    /** Carries the same code as {@code transfers.failure_reason}, so consumers
     * can tell why without calling back the orchestrator. */
    static OutboxRow forFailedTransfer(JsonMapper jsonMapper, Transfer transfer, String reason) {
        return forTransfer(jsonMapper, transfer, "TransferFailed", reason);
    }

    private static OutboxRow forTransfer(JsonMapper jsonMapper, Transfer transfer, String eventType, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transferId", transfer.getId().toString());
        payload.put("senderId", transfer.getSenderId().toString());
        payload.put("recipientId", transfer.getRecipientId().toString());
        payload.put("amount", transfer.getAmount().toPlainString());
        payload.put("currency", transfer.getCurrency());
        payload.put("eventType", eventType);
        if (reason != null) {
            payload.put("reason", reason);
        }
        return new OutboxRow(UUID.randomUUID(), "Transfer", transfer.getId(), eventType,
                jsonMapper.writeValueAsString(payload), null);
    }
}
