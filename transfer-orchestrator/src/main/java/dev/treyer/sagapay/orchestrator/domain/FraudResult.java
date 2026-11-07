package dev.treyer.sagapay.orchestrator.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** {@code eventId} identifies the message that carried the result, for deduplication. */
public record FraudResult(
        UUID eventId, UUID transferId, FraudDecision decision, BigDecimal score, List<String> reasons) {}
