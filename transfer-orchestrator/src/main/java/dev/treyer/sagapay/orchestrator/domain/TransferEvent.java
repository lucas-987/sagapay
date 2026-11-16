package dev.treyer.sagapay.orchestrator.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A fact of {@code payments.transfers}, as the activity stream reads it: the status the transfer
 * reached. {@code reason} is only present on a failure.
 */
public record TransferEvent(
        UUID eventId,
        TransferStatus outcome,
        UUID transferId,
        UUID senderId,
        UUID recipientId,
        BigDecimal amount,
        String currency,
        FailureReason reason) {}
