package dev.treyer.sagapay.orchestrator.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** A transfer fact as one of its parties is given it: {@code cause} is only the sender's. */
public record ActivityEvent(
        UUID eventId,
        TransferStatus outcome,
        UUID transferId,
        UUID senderId,
        UUID recipientId,
        BigDecimal amount,
        String currency,
        FailureReason cause) {}
