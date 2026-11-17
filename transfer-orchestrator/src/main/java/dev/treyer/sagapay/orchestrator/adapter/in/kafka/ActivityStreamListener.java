package dev.treyer.sagapay.orchestrator.adapter.in.kafka;

import dev.treyer.sagapay.common.event.TransferEventType;
import dev.treyer.sagapay.orchestrator.application.port.in.RelayTransferEventUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.TransferEvent;
import dev.treyer.sagapay.orchestrator.domain.FailureReason;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Reads the orchestrator's own topic. Each instance reads it in a group of its own, from the latest
 * offset: every instance sees every event, and none replays what was published before it started.
 */
@Component
public class ActivityStreamListener {

    private static final Logger log = LoggerFactory.getLogger(ActivityStreamListener.class);

    /** The other events of the topic are not the stream's: they are ignored without a warning. */
    private static final Map<String, TransferEvent.Outcome> OUTCOMES = Map.of(
            TransferEventType.TRANSFER_POSTED.cloudEventType(), TransferEvent.Outcome.POSTED,
            TransferEventType.TRANSFER_BLOCKED.cloudEventType(), TransferEvent.Outcome.BLOCKED,
            TransferEventType.TRANSFER_FAILED.cloudEventType(), TransferEvent.Outcome.FAILED);

    private final RelayTransferEventUseCase relay;
    private final JsonMapper jsonMapper;

    public ActivityStreamListener(RelayTransferEventUseCase relay, JsonMapper jsonMapper) {
        this.relay = relay;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(
            topics = "${kafka.topics.transfers}",
            groupId = "orchestrator-activity-#{T(java.util.UUID).randomUUID()}",
            containerFactory = "activityStreamListenerContainerFactory",
            properties = "auto.offset.reset=latest")
    public void onTransferEvent(ConsumerRecord<String, String> record) {
        TransferEventMessage message;
        try {
            message = record.value() == null ? null : jsonMapper.readValue(record.value(), TransferEventMessage.class);
        } catch (JacksonException e) {
            skip(record, "record is not readable", e);
            return;
        }
        if (message == null) {
            skip(record, record.value() == null ? "record has no value" : "record is not an event", null);
            return;
        }
        TransferEventMessage.Data data = message.data();
        if (message.id() == null
                || message.type() == null
                || data == null
                || data.transferId() == null
                || data.senderId() == null
                || data.recipientId() == null
                || data.amount() == null
                || data.currency() == null) {
            skip(record, "record lacks a field the stream needs", null);
            return;
        }
        TransferEvent.Outcome outcome = OUTCOMES.get(message.type());
        if (outcome == null) {
            return;
        }
        BigDecimal amount;
        FailureReason reason;
        try {
            amount = new BigDecimal(data.amount());
            reason = data.reason() == null ? null : FailureReason.valueOf(data.reason());
        } catch (IllegalArgumentException e) {
            // A NumberFormatException is an IllegalArgumentException: one catch covers both values.
            skip(record, "record has a value the stream cannot read", e);
            return;
        }
        relay.relay(new TransferEvent(
                message.id(),
                outcome,
                data.transferId(),
                data.senderId(),
                data.recipientId(),
                amount,
                data.currency(),
                reason));
    }

    /** A null cause is ignored by the logger. */
    private static void skip(ConsumerRecord<String, String> record, String reason, Exception cause) {
        log.warn(
                "activity stream skipped a record",
                kv("messaging.destination.name", record.topic()),
                kv("messaging.destination.partition.id", record.partition()),
                kv("messaging.kafka.offset", record.offset()),
                kv("reason", reason),
                cause);
    }
}
