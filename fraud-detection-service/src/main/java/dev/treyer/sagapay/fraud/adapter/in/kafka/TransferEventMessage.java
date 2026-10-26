package dev.treyer.sagapay.fraud.adapter.in.kafka;

import java.math.BigDecimal;
import java.util.UUID;

/** The part of a transfer CloudEvent that fraud reads; every other field is ignored. */
public record TransferEventMessage(String id, Data data) {

    public record Data(
            String eventType, UUID transferId, UUID senderId, UUID recipientId, BigDecimal amount, String currency) {}
}
