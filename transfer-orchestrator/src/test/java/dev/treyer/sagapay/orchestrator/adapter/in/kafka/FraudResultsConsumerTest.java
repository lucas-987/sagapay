package dev.treyer.sagapay.orchestrator.adapter.in.kafka;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The topics have one partition, so records are consumed in the order they are sent. */
@Import({TestcontainersConfiguration.class, FraudResultsConsumerTest.TestBeans.class})
// Only the listener may move the transfers.
@TestPropertySource(properties = "saga.reprise.sweep-interval-ms=3600000")
@SpringBootTest
class FraudResultsConsumerTest {

    private static final String RESULTS_TOPIC = "payments.fraud.results";
    private static final String DEAD_LETTER_TOPIC = "payments.fraud.results.DLT";
    private static final Duration PATIENCE = Duration.ofSeconds(60);

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingLedger ledger;

    @BeforeEach
    void forgetEarlierPosts() {
        ledger.postedTransferIds.clear();
    }

    private UUID insertTransfer(String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency, status, reservation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("25.0000"),
                "EUR",
                status,
                UUID.randomUUID());
        return id;
    }

    private String clearedEvent(UUID eventId, UUID transferId) {
        return """
                {"id":"%s","source":"fraud-detection-service","type":"TransferCleared.v1",\
                "time":"2026-11-01T10:00:00Z","subject":"%s","traceparent":null,\
                "data":{"transferId":"%s","senderId":"%s","decision":"CLEARED","score":0.1500,\
                "reasons":["AMOUNT_ROUND"],"modelVersion":"v1","ts":"2026-11-01T10:00:00Z"}}""".formatted(eventId, transferId, transferId, UUID.randomUUID());
    }

    private void send(UUID transferId, String value) {
        kafka.send(RESULTS_TOPIC, transferId.toString(), value).join();
    }

    private void awaitStatus(UUID transferId, String status) {
        await().atMost(PATIENCE)
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "select status::text from transfers where id = ?", String.class, transferId))
                        .isEqualTo(status));
    }

    private int processedEventCount(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from processed_events where event_id = ?", Integer.class, eventId);
    }

    private List<String> deadLetteredValues() {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafkaContainer.getBootstrapServers(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest");
        List<String> values = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            TopicPartition partition = new TopicPartition(DEAD_LETTER_TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            while (consumer.position(partition) < end) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                    values.add(record.value());
                }
            }
        }
        return values;
    }

    @Test
    @Tag("M3-S03")
    @DisplayName("A screening result delivered twice is applied once")
    void aResultDeliveredTwiceIsAppliedOnce() {
        UUID screened = insertTransfer("SCREENING");
        UUID marker = insertTransfer("SCREENING");
        UUID eventId = UUID.randomUUID();
        String event = clearedEvent(eventId, screened);

        send(screened, event);
        send(screened, event);
        send(marker, clearedEvent(UUID.randomUUID(), marker));
        awaitStatus(marker, "POSTED");

        assertThat(ledger.postedTransferIds).containsExactly(screened.toString(), marker.toString());
        assertThat(processedEventCount(eventId)).isEqualTo(1);
    }

    @Test
    @Tag("M3-S04")
    @DisplayName("A result for a transfer no longer being screened is recorded and ignored")
    void aResultForATransferThatLeftScreeningIsRecordedAndIgnored() {
        UUID left = insertTransfer("FAILED");
        UUID marker = insertTransfer("SCREENING");
        UUID eventId = UUID.randomUUID();

        send(left, clearedEvent(eventId, left));
        send(marker, clearedEvent(UUID.randomUUID(), marker));
        awaitStatus(marker, "POSTED");

        assertThat(processedEventCount(eventId)).isEqualTo(1);
        assertThat(ledger.postedTransferIds).containsExactly(marker.toString());
        assertThat(jdbcTemplate.queryForObject("select status::text from transfers where id = ?", String.class, left))
                .isEqualTo("FAILED");
    }

    @Test
    @Tag("M3-S06")
    @DisplayName("An unreadable screening result is set aside without blocking the next ones")
    void anUnreadableResultIsDeadLetteredAndTheNextOneIsApplied() {
        UUID next = insertTransfer("SCREENING");
        String unreadable = "not json {" + UUID.randomUUID();

        send(UUID.randomUUID(), unreadable);
        send(next, clearedEvent(UUID.randomUUID(), next));
        awaitStatus(next, "POSTED");

        await().atMost(PATIENCE)
                .untilAsserted(() -> assertThat(deadLetteredValues()).containsExactly(unreadable));
    }

    static class RecordingLedger implements LedgerPort {
        private final List<String> postedTransferIds = new CopyOnWriteArrayList<>();

        @Override
        public ReservationResult checkAndReserve(String transferId, UUID fromAccountId, Money amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void postTransfer(String transferId, UUID fromAccountId, UUID toAccountId, Money amount) {
            postedTransferIds.add(transferId);
        }

        @Override
        public void releaseReservation(String transferId, UUID reservationId) {
            throw new UnsupportedOperationException();
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        RecordingLedger recordingLedger() {
            return new RecordingLedger();
        }

        @Bean
        org.apache.kafka.clients.admin.NewTopic fraudResultsTopic() {
            return TopicBuilder.name(RESULTS_TOPIC).partitions(1).build();
        }

        @Bean
        org.apache.kafka.clients.admin.NewTopic fraudResultsDeadLetterTopic() {
            return TopicBuilder.name(DEAD_LETTER_TOPIC).partitions(1).build();
        }
    }
}
