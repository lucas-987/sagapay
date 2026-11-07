package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.common.event.TransferEventType;
import dev.treyer.sagapay.orchestrator.domain.FailureReason;
import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class OutboxEvents {

    private OutboxEvents() {}

    static OutboxRow forTransfer(JsonMapper jsonMapper, Transfer transfer, TransferEventType eventType, Instant now) {
        return forTransfer(jsonMapper, transfer, eventType, null, now);
    }

    /** Carries the same code as {@code transfers.failure_reason}, so consumers
     * can tell why without calling back the orchestrator. */
    static OutboxRow forFailedTransfer(JsonMapper jsonMapper, Transfer transfer, FailureReason reason, Instant now) {
        return forTransfer(jsonMapper, transfer, TransferEventType.TRANSFER_FAILED, reason, now);
    }

    private static OutboxRow forTransfer(
            JsonMapper jsonMapper, Transfer transfer, TransferEventType eventType, FailureReason reason, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transferId", transfer.getId().toString());
        payload.put("senderId", transfer.getSenderId().toString());
        payload.put("recipientId", transfer.getRecipientId().toString());
        payload.put("amount", transfer.getAmount().toPlainString());
        payload.put("currency", transfer.getCurrency());
        payload.put("eventType", eventType.wireName());
        if (reason != null) {
            payload.put("reason", reason.name());
        }
        return new OutboxRow(
                UUID.randomUUID(),
                "Transfer",
                transfer.getId(),
                eventType.wireName(),
                jsonMapper.writeValueAsString(payload),
                null,
                now);
    }
}
