package dev.treyer.sagapay.fraud.adapter.out.messaging;

import dev.treyer.sagapay.common.event.CloudEvent;
import dev.treyer.sagapay.fraud.application.port.out.OutboxRelayPort;
import dev.treyer.sagapay.fraud.domain.OutboxMessage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The row locks must be held until the rows are marked published, hence one transaction around
 * the batch. A failed publish leaves its row, and the rows after it, for the next run.
 */
@Component
class OutboxPoller {

    static final String TOPIC = "payments.fraud.results";
    static final String SOURCE = "fraud-detection-service";
    private static final int BATCH_SIZE = 100;
    private static final String TRACEPARENT_VERSION = "00";

    private final OutboxRelayPort outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final Tracer tracer;

    OutboxPoller(
            OutboxRelayPort outbox, KafkaTemplate<String, String> kafkaTemplate, JsonMapper jsonMapper, Tracer tracer) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.tracer = tracer;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:500}")
    @Transactional
    public void drain() {
        List<OutboxMessage> batch = outbox.findUnpublishedForUpdateSkipLocked(BATCH_SIZE);
        for (OutboxMessage message : batch) {
            publish(message);
            outbox.markPublished(message.id());
        }
    }

    private void publish(OutboxMessage message) {
        CloudEvent<JsonNode> event = CloudEvent.of(
                message.id(),
                SOURCE,
                message.eventType() + ".v1",
                message.aggregateId(),
                jsonMapper.readTree(message.payload()));

        // A new span per publish: the scheduler thread has no trace to inherit. The W3C header is
        // built by hand because the auto-configured Propagator injects no field in this tracing setup.
        Span span = tracer.nextSpan().name("outbox-publish").start();
        try {
            TraceContext context = span.context();
            String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
            String traceparent = TRACEPARENT_VERSION + "-" + context.traceId() + "-" + context.spanId() + "-" + flags;

            ProducerRecord<String, String> record =
                    new ProducerRecord<>(TOPIC, message.aggregateId(), jsonMapper.writeValueAsString(event));
            record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record).join();
        } finally {
            span.end();
        }
    }
}
