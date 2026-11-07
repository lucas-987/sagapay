package dev.treyer.sagapay.fraud.adapter.out.messaging;

import dev.treyer.sagapay.common.event.CloudEvent;
import dev.treyer.sagapay.fraud.application.port.out.OutboxRelayPort;
import dev.treyer.sagapay.fraud.config.OutboxProperties;
import dev.treyer.sagapay.fraud.domain.OutboxMessage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * One instance publishes at a time, hence the lock: two at once could send the events of a key out
 * of order. A run stops at a temporary failure, so that the rows already sent stay marked and those
 * after it keep their order; a permanent one sets its key aside, and the other keys go on.
 */
@Component
class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    static final String TOPIC = "payments.fraud.results";
    private static final String TRACEPARENT_VERSION = "00";

    private final OutboxRelayPort outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final Tracer tracer;
    private final int batchSize;

    OutboxPoller(
            OutboxRelayPort outbox,
            KafkaTemplate<String, String> kafkaTemplate,
            JsonMapper jsonMapper,
            Tracer tracer,
            OutboxProperties properties) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.tracer = tracer;
        this.batchSize = properties.batchSize();
    }

    // The first run waits one interval too, so that a test setting a long interval is the only caller.
    @Scheduled(
            fixedDelayString = "${outbox.poll-interval-ms:500}",
            initialDelayString = "${outbox.poll-interval-ms:500}")
    @Transactional
    public void drain() {
        if (!outbox.tryLockPublisher()) {
            return;
        }
        Set<UUID> quarantined = new HashSet<>();
        for (OutboxMessage message : outbox.findPublishable(batchSize)) {
            UUID key = message.aggregateId();
            if (quarantined.contains(key)) {
                continue;
            }
            try {
                publish(message);
            } catch (RuntimeException e) {
                Optional<Throwable> permanent = PermanentPublishFailure.causeOf(e);
                if (permanent.isEmpty()) {
                    log.warn(
                            "outbox publish failed, retrying on the next run",
                            kv("outbox.id", message.id()),
                            kv("event.type", message.eventType()),
                            e);
                    return;
                }
                log.error(
                        "outbox key quarantined",
                        kv("outbox.id", message.id()),
                        kv("event.type", message.eventType()),
                        e);
                outbox.quarantine(key, message.id(), permanent.get().toString());
                quarantined.add(key);
                continue;
            }
            outbox.markPublished(message.id());
        }
    }

    private void publish(OutboxMessage message) {
        CloudEvent<JsonNode> event = FraudResultEvents.of(message, jsonMapper);

        // A new span per publish: the scheduler thread has no trace to inherit. The W3C header is
        // built by hand because the auto-configured Propagator injects no field in this tracing setup.
        Span span = tracer.nextSpan().name("outbox-publish").start();
        try {
            TraceContext context = span.context();
            String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
            String traceparent = TRACEPARENT_VERSION + "-" + context.traceId() + "-" + context.spanId() + "-" + flags;

            ProducerRecord<String, String> record =
                    new ProducerRecord<>(TOPIC, message.aggregateId().toString(), jsonMapper.writeValueAsString(event));
            record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record).join();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
