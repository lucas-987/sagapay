package dev.treyer.sagapay.orchestrator.adapter.in.kafka;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.application.port.in.ApplyFraudResultUseCase;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.application.service.SagaService;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A result whose application fails with an exception worth retrying (the database briefly down,
 * for instance) is tried again after the back-off, then set aside once the retries are used up.
 * The back-off is the production one: three retries, one second apart.
 */
@Import({TestcontainersConfiguration.class, FraudResultsRetryTest.TestBeans.class})
@TestPropertySource(properties = "saga.reprise.sweep-interval-ms=3600000")
@SpringBootTest
class FraudResultsRetryTest {

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

    @Autowired
    private FlakyApplyFraudResult flakyUseCase;

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
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::transfer_status, ?)
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

    /** Only this transfer's records: the dead-letter topic is shared by the tests of the class. */
    private List<String> deadLetteredFor(UUID transferId) {
        return deadLetteredValues().stream()
                .filter(value -> value.contains(transferId.toString()))
                .toList();
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
    @DisplayName("A result whose application fails twice is applied once, at the third attempt")
    void aResultFailingTwiceIsAppliedOnceOnTheThirdAttempt() {
        UUID flaky = insertTransfer("SCREENING");
        UUID eventId = UUID.randomUUID();
        flakyUseCase.failuresLeft.put(flaky, new AtomicInteger(2));

        send(flaky, clearedEvent(eventId, flaky));
        awaitStatus(flaky, "POSTED");

        assertThat(flakyUseCase.attempts.get(flaky).get()).isEqualTo(3);
        assertThat(ledger.postedTransferIds).containsExactly(flaky.toString());
        assertThat(processedEventCount(eventId)).isEqualTo(1);
        assertThat(deadLetteredFor(flaky)).isEmpty();
    }

    @Test
    @DisplayName("A result that always fails is dead-lettered once after its retries, and the next one is applied")
    void aResultAlwaysFailingIsDeadLetteredOnceAndTheNextOneIsApplied() {
        UUID broken = insertTransfer("SCREENING");
        UUID next = insertTransfer("SCREENING");
        String failing = clearedEvent(UUID.randomUUID(), broken);
        flakyUseCase.failuresLeft.put(broken, new AtomicInteger(Integer.MAX_VALUE));

        send(broken, failing);
        send(next, clearedEvent(UUID.randomUUID(), next));
        awaitStatus(next, "POSTED");

        await().atMost(PATIENCE)
                .untilAsserted(() -> assertThat(deadLetteredFor(broken)).containsExactly(failing));
        // The first attempt and the three retries.
        assertThat(flakyUseCase.attempts.get(broken).get()).isEqualTo(4);
        assertThat(ledger.postedTransferIds).containsExactly(next.toString());
        assertThat(jdbcTemplate.queryForObject("select status::text from transfers where id = ?", String.class, broken))
                .isEqualTo("SCREENING");
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

    /** Fails the first attempts at applying a result, as configured per transfer, then delegates. */
    static class FlakyApplyFraudResult implements ApplyFraudResultUseCase {
        private final SagaService delegate;
        private final Map<UUID, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();
        private final Map<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();

        FlakyApplyFraudResult(SagaService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void applyFraudResult(FraudResult result) {
            attempts.computeIfAbsent(result.transferId(), id -> new AtomicInteger())
                    .incrementAndGet();
            AtomicInteger left = failuresLeft.get(result.transferId());
            if (left != null && left.getAndDecrement() > 0) {
                throw new IllegalStateException("database briefly down");
            }
            delegate.applyFraudResult(result);
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        FlakyApplyFraudResult flakyApplyFraudResult(SagaService delegate) {
            return new FlakyApplyFraudResult(delegate);
        }

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
