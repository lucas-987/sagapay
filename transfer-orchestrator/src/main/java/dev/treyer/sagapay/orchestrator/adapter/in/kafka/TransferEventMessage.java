package dev.treyer.sagapay.orchestrator.adapter.in.kafka;

import java.util.UUID;

/** The part of a {@code payments.transfers} CloudEvent the stream reads; the rest is ignored. */
record TransferEventMessage(UUID id, String type, Data data) {

    record Data(UUID transferId, UUID senderId, UUID recipientId, String amount, String currency, String reason) {}
}
