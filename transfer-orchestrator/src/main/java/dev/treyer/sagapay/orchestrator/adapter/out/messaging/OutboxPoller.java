package dev.treyer.sagapay.orchestrator.adapter.out.messaging;

import dev.treyer.sagapay.common.event.CloudEvent;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.OutboxRepository;
import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
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
 * Adapter out — drains {@code outbox} to Kafka. {@code @Transactional}: {@code
 * findUnpublishedForUpdateSkipLocked}'s {@code FOR UPDATE} lock is only held
 * for the duration of an active transaction, and must still be held while
 * {@code markPublished} runs (same batch, same lock) -- unlike {@code
 * SagaService}'s other methods, there's no remote call inside this
 * transaction's critical section: {@code kafkaTemplate.send(...).join()}
 * waits for the broker's ack, not a saga step, and failing to publish must
 * roll back nothing (the row simply stays unpublished for the next run).
 */
@Component
public class OutboxPoller {

    private static final String TOPIC = "payments.transfers";
    private static final String SOURCE = "transfer-orchestrator";
    private static final String TRACEPARENT_VERSION = "00";

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final Tracer tracer;

    public OutboxPoller(OutboxRepository outbox, KafkaTemplate<String, String> kafkaTemplate, JsonMapper jsonMapper,
                         Tracer tracer) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.tracer = tracer;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:500}")
    @Transactional
    public void drain() {
        List<OutboxRow> batch = outbox.findUnpublishedForUpdateSkipLocked(100);
        for (OutboxRow row : batch) {
            publish(row);
            outbox.markPublished(row.getId());
        }
    }

    private void publish(OutboxRow row) {
        JsonNode payload = jsonMapper.readTree(row.getPayload());
        String key = payload.get("senderId").asString();
        CloudEvent<JsonNode> event = CloudEvent.now(SOURCE, row.getEventType() + ".v1",
                row.getAggregateId().toString(), payload);

        // A fresh span per publish, not a reused "current" one: this runs on the
        // scheduler's own thread, outside any request/saga-step trace -- the
        // traceparent header exists to let a future consumer correlate back to
        // this publish event itself, not to inherit a caller's trace that
        // doesn't exist here.
        //
        // Built directly from TraceContext (W3C Trace Context format:
        // version-traceId-spanId-flags, https://www.w3.org/TR/trace-context/)
        // rather than via the auto-configured Propagator bean: that bean
        // resolved to one with an empty field list in this project's current
        // config (produced no header at all when actually exercised, verified
        // by running OutboxPollerTest against it) -- a fixed, standardized wire
        // format is simple enough to not need to depend on diagnosing why.
        Span span = tracer.nextSpan().name("outbox-publish").start();
        try {
            TraceContext context = span.context();
            String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
            String traceparent = TRACEPARENT_VERSION + "-" + context.traceId() + "-" + context.spanId() + "-" + flags;

            ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key, jsonMapper.writeValueAsString(event));
            // A transport header, not a payload field: a consumer reads this
            // via ConsumerRecord.headers(), never from the deserialized
            // business payload.
            record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record).join();
        } finally {
            span.end();
        }
    }
}
