package dev.treyer.sagapay.fraud.domain;

import java.util.UUID;

/** {@code payload} is JSON already serialized by the caller. */
public record OutboxMessage(UUID id, String aggregateType, String aggregateId, String eventType, String payload) {}
