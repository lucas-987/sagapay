package dev.treyer.sagapay.orchestrator.adapter.out.messaging;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.OutboxRepository;
import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OutboxPollerTest {

    @Autowired
    private OutboxPoller outboxPoller;

    @Autowired
    private OutboxRepository outboxRepository;
    // Not the bootstrap-servers property: @ServiceConnection bypasses it, so it
    // still holds the static default address.
    @Autowired
    private KafkaContainer kafkaContainer;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-poller-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of("payments.transfers"));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    private OutboxRow newUnpublishedRow(UUID senderId) {
        String payload = "{\"senderId\":\"" + senderId + "\"}";
        return outboxRepository.saveAndFlush(
                new OutboxRow(UUID.randomUUID(), "Transfer", UUID.randomUUID(), "TransferInitiated", payload, null));
    }

    /** Polls for the whole timeout: stopping at the expected count would hide a
     * late duplicate. Other tests' messages are filtered out by key. */
    private List<ConsumerRecord<String, String>> pollFor(Set<String> expectedKeys, Duration timeout) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (expectedKeys.contains(record.key())) {
                    found.add(record);
                }
            }
        }
        return found;
    }

    @Test
    void drainPublishesAnUnpublishedRowAndMarksItPublished() {
        UUID senderId = UUID.randomUUID();
        OutboxRow row = newUnpublishedRow(senderId);

        outboxPoller.drain();

        List<ConsumerRecord<String, String>> found = pollFor(Set.of(senderId.toString()), Duration.ofSeconds(15));
        assertThat(found).hasSize(1);
        assertThat(outboxRepository.findById(row.getId()).orElseThrow().getPublishedAt())
                .isNotNull();
    }

    @Test
    void drainPropagatesANonEmptyTraceparentAsAKafkaHeaderNotInThePayload() {
        UUID senderId = UUID.randomUUID();
        newUnpublishedRow(senderId);

        outboxPoller.drain();

        List<ConsumerRecord<String, String>> found = pollFor(Set.of(senderId.toString()), Duration.ofSeconds(15));
        ConsumerRecord<String, String> record = found.get(0);
        Header traceparent = record.headers().lastHeader("traceparent");

        assertThat(traceparent).isNotNull();
        assertThat(new String(traceparent.value(), StandardCharsets.UTF_8)).isNotBlank();
        // A transport header, not a payload field.
        assertThat(record.value()).doesNotContain("\"data\":{\"traceparent\"");
    }

    @Test
    void concurrentDrainsPublishEachRowExactlyOnce() throws Exception {
        Set<String> senderIds = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            UUID senderId = UUID.randomUUID();
            senderIds.add(senderId.toString());
            newUnpublishedRow(senderId);
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(pool.submit(outboxPoller::drain));
            futures.add(pool.submit(outboxPoller::drain));
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        List<ConsumerRecord<String, String>> found = pollFor(senderIds, Duration.ofSeconds(20));
        // Records, not distinct keys, so a duplicate publish is caught.
        assertThat(found).hasSize(senderIds.size());
    }
}
