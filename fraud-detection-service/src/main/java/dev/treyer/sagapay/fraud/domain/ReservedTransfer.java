package dev.treyer.sagapay.fraud.domain;

import dev.treyer.sagapay.common.domain.Money;

import java.util.UUID;

/** A {@code FundsReserved} event; {@code eventId} is the CloudEvent id used for deduplication. */
public record ReservedTransfer(UUID eventId, UUID transferId, UUID senderId, UUID recipientId, Money amount) {}
