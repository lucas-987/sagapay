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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "outbox.poll-interval-ms=3600000")
class OutboxPollerTest {

    @Autowired
    private OutboxPoller outboxPoller;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private DataSource dataSource;

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
        awaitAssignment();
    }

    /** Joining a group on a fresh broker can take longer than the polling window of a test: the
     * test starts once the consumer is assigned, so that the window only waits for the records. */
    private void awaitAssignment() {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (consumer.assignment().isEmpty() && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(100));
        }
        assertThat(consumer.assignment()).isNotEmpty();
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    private OutboxRow newUnpublishedRow(UUID senderId) {
        String payload = "{\"senderId\":\"" + senderId + "\"}";
        return outboxRepository.saveAndFlush(new OutboxRow(
                UUID.randomUUID(),
                "Transfer",
                UUID.randomUUID(),
                senderId.toString(),
                "TransferInitiated",
                payload,
                null,
                Instant.now()));
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
    void aRowPublishedTwiceCarriesTheSameCloudEventIdEqualToTheRowId() {
        UUID senderId = UUID.randomUUID();
        OutboxRow row = newUnpublishedRow(senderId);

        outboxPoller.drain();
        jdbcTemplate.update("update outbox set published_at = null where id = ?", row.getId());
        outboxPoller.drain();

        List<ConsumerRecord<String, String>> found = pollFor(Set.of(senderId.toString()), Duration.ofSeconds(15));
        assertThat(found).hasSize(2);
        assertThat(found)
                .allSatisfy(record -> assertThat(
                                jsonMapper.readTree(record.value()).get("id").asString())
                        .isEqualTo(row.getId().toString()));
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

    @Test
    @Tag("M3-S55")
    @DisplayName("Only one instance publishes at a time")
    void nothingIsPublishedWhileAnotherInstanceHoldsThePublisherLock() throws Exception {
        UUID senderId = UUID.randomUUID();
        OutboxRow row;
        try (Connection otherInstance = dataSource.getConnection()) {
            otherInstance.setAutoCommit(false);
            try (Statement statement = otherInstance.createStatement()) {
                statement.execute("select pg_advisory_xact_lock(1)");
            }
            row = newUnpublishedRow(senderId);

            outboxPoller.drain();

            assertThat(outboxRepository.findById(row.getId()).orElseThrow().getPublishedAt())
                    .isNull();
            otherInstance.rollback();
        }

        outboxPoller.drain();

        assertThat(pollFor(Set.of(senderId.toString()), Duration.ofSeconds(10))).hasSize(1);
    }
}
