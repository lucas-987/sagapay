package dev.treyer.sagapay.orchestrator.domain;

import java.util.UUID;

/** What the stream pushes to one user; {@code cause} is only given to the sender of a failure. */
public record ActivityEvent(
        String id,
        String name,
        UUID transferId,
        UUID senderId,
        UUID recipientId,
        String amount,
        String currency,
        String cause) {}
