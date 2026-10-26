package dev.treyer.sagapay.fraud.application.service;

import dev.treyer.sagapay.fraud.application.port.out.FraudCasePort;
import dev.treyer.sagapay.fraud.application.port.out.OutboxPort;
import dev.treyer.sagapay.fraud.application.port.out.ProcessedEventPort;
import dev.treyer.sagapay.fraud.domain.FraudAssessment;
import dev.treyer.sagapay.fraud.domain.FraudCase;
import dev.treyer.sagapay.fraud.domain.FraudDecision;
import dev.treyer.sagapay.fraud.domain.OutboxMessage;
import dev.treyer.sagapay.fraud.domain.ReservedTransfer;
import dev.treyer.sagapay.fraud.domain.SenderActivity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The one transaction of a screening. A separate bean: {@code @Transactional} is ignored on
 * calls within the same class.
 */
@Component
class DecisionWriter {

    static final String CONSUMER = "fraud-screening";

    private final ProcessedEventPort processedEvents;
    private final FraudCasePort cases;
    private final OutboxPort outbox;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    DecisionWriter(
            ProcessedEventPort processedEvents,
            FraudCasePort cases,
            OutboxPort outbox,
            JsonMapper jsonMapper,
            Clock clock) {
        this.processedEvents = processedEvents;
        this.cases = cases;
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** @return false when the event was already handled, in which case nothing is written. */
    @Transactional
    boolean store(ReservedTransfer transfer, SenderActivity activity, FraudAssessment assessment) {
        if (!processedEvents.markProcessed(transfer.eventId(), CONSUMER)) {
            return false;
        }
        if (assessment.decision() == FraudDecision.FLAGGED) {
            cases.open(new FraudCase(
                    transfer.transferId(),
                    transfer.senderId(),
                    transfer.amount(),
                    assessment.score(),
                    assessment.reasons(),
                    activity));
        }
        outbox.save(resultOf(transfer, assessment));
        return true;
    }

    private OutboxMessage resultOf(ReservedTransfer transfer, FraudAssessment assessment) {
        String eventType = assessment.decision() == FraudDecision.FLAGGED ? "TransferFlagged" : "TransferCleared";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transferId", transfer.transferId().toString());
        payload.put("senderId", transfer.senderId().toString());
        payload.put("decision", assessment.decision().name());
        payload.put("score", assessment.score());
        payload.put("reasons", assessment.reasons().stream().map(Enum::name).toList());
        payload.put("modelVersion", assessment.modelVersion());
        payload.put("ts", clock.instant().toString());
        return new OutboxMessage(
                UUID.randomUUID(),
                "Transfer",
                transfer.transferId().toString(),
                eventType,
                jsonMapper.writeValueAsString(payload));
    }
}
