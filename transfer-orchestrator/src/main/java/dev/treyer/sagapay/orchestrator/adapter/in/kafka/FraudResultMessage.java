package dev.treyer.sagapay.orchestrator.adapter.in.kafka;

import dev.treyer.sagapay.orchestrator.domain.FraudDecision;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** The part of a fraud result CloudEvent that the orchestrator reads; every other field is ignored. */
record FraudResultMessage(UUID id, Data data) {

    record Data(UUID transferId, FraudDecision decision, BigDecimal score, List<String> reasons) {}
}
