package dev.treyer.sagapay.orchestrator.domain;

import java.util.UUID;

/**
 * A fact of {@code payments.transfers}, as the activity stream reads it. {@code type} is the
 * event's type as published, version included; {@code reason} is only present on a failure.
 */
public record TransferEvent(
        UUID eventId,
        String type,
        UUID transferId,
        UUID senderId,
        UUID recipientId,
        String amount,
        String currency,
        String reason) {}
