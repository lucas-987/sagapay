package dev.treyer.sagapay.fraud;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.fraud.application.port.in.ScreenTransferUseCase;
import dev.treyer.sagapay.fraud.domain.ReservedTransfer;
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
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FraudScreeningTest {

    private static final String TOPIC = "payments.transfers";
    private static final String DEAD_LETTER_TOPIC = "payments.transfers.DLT";
    private static final Duration PATIENCE = Duration.ofSeconds(60);

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ScreenTransferUseCase screening;

    @Autowired
    private DefaultErrorHandler errorHandler;

    private final List<String> failedDeliveries = new CopyOnWriteArrayList<>();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();

    @BeforeEach
    void recordFailedDeliveries() {
        errorHandler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) {
                failedDeliveries.add(record.key() + " attempt " + deliveryAttempt);
            }
        });
    }

    private static String event(
            String eventId, String eventType, UUID transfer, UUID sender, UUID recipient, String amount) {
        return """
                {"id":"%s","source":"transfer-orchestrator","type":"%s.v1","time":"2026-03-01T10:15:30Z",\
                "subject":"%s","traceparent":null,"data":{"eventType":"%s","transferId":"%s","senderId":"%s",\
                "recipientId":"%s","amount":"%s","currency":"EUR"}}""".formatted(eventId, eventType, transfer, eventType, transfer, sender, recipient, amount);
    }

    private void send(String value) {
        kafka.send(TOPIC, sender.toString(), value).join();
    }

    private void sendReserved(String eventId, UUID transfer, UUID to, String amount) {
        send(event(eventId, "FundsReserved", transfer, sender, to, amount));
    }

    private int countOf(String table, String column, String value) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
    }

    private int processedEvents() {
        return jdbc.queryForObject("select count(*) from processed_events", Integer.class);
    }

    private int outboxRows() {
        return jdbc.queryForObject("select count(*) from outbox", Integer.class);
    }

    private void awaitResultFor(UUID transfer) {
        await().atMost(PATIENCE)
                .untilAsserted(() -> assertThat(countOf("outbox", "aggregate_id", transfer.toString()))
                        .isGreaterThanOrEqualTo(1));
    }

    private String outboxEventTypeOf(UUID transfer) {
        return jdbc.queryForObject(
                "select event_type from outbox where aggregate_id = ?", String.class, transfer.toString());
    }

    private String resultOf(UUID transfer) {
        return jdbc.queryForObject(
                "select payload::text from outbox where aggregate_id = ?", String.class, transfer.toString());
    }

    private List<String> reasonsOf(UUID transfer) {
        return jsonMapper
                .readTree(resultOf(transfer))
                .get("reasons")
                .valueStream()
                .map(r -> r.asString())
                .toList();
    }

    @Test
    @Tag("M3-S13")
    @DisplayName("A flagged transfer opens one fraud case")
    void flaggedTransferOpensOneFraudCase() {
        UUID transfer = UUID.randomUUID();

        sendReserved(UUID.randomUUID().toString(), transfer, recipient, "500.00");
        awaitResultFor(transfer);

        List<Map<String, Object>> cases = jdbc.queryForList(
                "select status, transfer_outcome, score::text as score, array_to_string(reasons, ',') as reasons"
                        + " from fraud_cases where transfer_id = ?",
                transfer.toString());
        assertThat(cases).hasSize(1);
        Map<String, Object> flagged = cases.getFirst();
        assertThat(flagged.get("status")).isEqualTo("OPEN");
        assertThat(flagged.get("transfer_outcome")).isNull();
        assertThat(flagged.get("score")).isEqualTo("0.7000");
        assertThat(flagged.get("reasons")).isEqualTo("NEW_RECIPIENT,AMOUNT_ROUND");
        assertThat(outboxEventTypeOf(transfer)).isEqualTo("TransferFlagged");
    }

    @Test
    @Tag("M3-S15")
    @DisplayName("The rules see the sender's history before the current transfer")
    void rulesSeeTheHistoryBeforeTheCurrentTransfer() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        sendReserved(UUID.randomUUID().toString(), first, recipient, "20.00");
        sendReserved(UUID.randomUUID().toString(), second, recipient, "20.00");
        awaitResultFor(first);
        awaitResultFor(second);

        assertThat(reasonsOf(first)).containsExactly("NEW_RECIPIENT");
        assertThat(reasonsOf(second)).isEmpty();
    }

    @Test
    @Tag("M3-S16")
    @DisplayName("A decision that fails to be stored leaves the sender's history unchanged")
    void failedDecisionLeavesTheHistoryUnchanged() {
        UUID transfer = UUID.randomUUID();
        UUID otherRecipient = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        redis.opsForValue().set("vel:" + sender + ":1h", "2");
        redis.opsForSet().add("recipients:" + sender, otherRecipient.toString());
        jdbc.update(
                "insert into fraud_cases (transfer_id, sender_id, amount, score, reasons)"
                        + " values (?, ?, 1, 0.5, array['VELOCITY_1H'])",
                transfer.toString(),
                sender.toString());

        assertThatThrownBy(() -> screening.screen(
                        new ReservedTransfer(eventId, transfer, sender, recipient, Money.of("500.00", "EUR"))))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(countOf("processed_events", "event_id", eventId)).isZero();
        assertThat(countOf("outbox", "aggregate_id", transfer.toString())).isZero();
        assertThat(redis.opsForValue().get("vel:" + sender + ":1h")).isEqualTo("2");
        assertThat(redis.opsForSet().members("recipients:" + sender)).containsExactly(otherRecipient.toString());
    }

    @Test
    @Tag("M3-S17")
    @DisplayName("An unreadable transfer event is set aside without blocking the next ones")
    void unreadableEventIsSetAsideWithoutBlockingTheNextOnes() {
        UUID transfer = UUID.randomUUID();
        int partitionPastTheDeadLetterTopic = 3;
        kafka.send(TOPIC, partitionPastTheDeadLetterTopic, sender.toString(), "{not json")
                .join();
        kafka.send(
                        TOPIC,
                        partitionPastTheDeadLetterTopic,
                        sender.toString(),
                        event(UUID.randomUUID().toString(), "FundsReserved", transfer, sender, recipient, "20.00"))
                .join();

        awaitResultFor(transfer);

        List<ConsumerRecord<String, String>> deadLetters = deadLettersOf(sender.toString());
        assertThat(deadLetters).hasSize(1);
        assertThat(deadLetters.getFirst().value()).isEqualTo("{not json");
        assertThat(outboxEventTypeOf(transfer)).isEqualTo("TransferCleared");
    }

    @Test
    @DisplayName("A reservation without its recipient is set aside as dead letter")
    void reservationWithoutRecipientIsDeadLettered() {
        UUID transfer = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        send("""
                {"id":"%s","data":{"eventType":"FundsReserved","transferId":"%s","senderId":"%s","amount":"20.00","currency":"EUR"}}""".formatted(eventId, transfer, sender));

        List<ConsumerRecord<String, String>> deadLetters = deadLettersOf(sender.toString());

        assertThat(deadLetters).hasSize(1);
        assertThat(jsonMapper.readTree(deadLetters.getFirst().value()))
                .isEqualTo(jsonMapper.readTree("""
                {"id":"%s","data":{"eventType":"FundsReserved","transferId":"%s","senderId":"%s","recipientId":null,"amount":20.00,"currency":"EUR"}}""".formatted(eventId, transfer, sender)));
        assertThat(countOf("processed_events", "event_id", eventId)).isZero();
    }

    @Test
    @Tag("M3-S18")
    @DisplayName("Transfer events other than a reservation are ignored by fraud")
    void eventsOtherThanAReservationAreIgnored() {
        UUID ignoredRecipient = UUID.randomUUID();
        Map<String, UUID> ignored = new LinkedHashMap<>();
        for (String type : List.of("TransferInitiated", "TransferPosted", "TransferFailed")) {
            String eventId = UUID.randomUUID().toString();
            UUID transfer = UUID.randomUUID();
            ignored.put(eventId, transfer);
            send(event(eventId, type, transfer, sender, ignoredRecipient, "500.00"));
        }
        UUID reserved = UUID.randomUUID();
        sendReserved(UUID.randomUUID().toString(), reserved, recipient, "20.00");

        awaitResultFor(reserved);

        for (Map.Entry<String, UUID> e : ignored.entrySet()) {
            assertThat(countOf("processed_events", "event_id", e.getKey())).isZero();
            assertThat(countOf("outbox", "aggregate_id", e.getValue().toString()))
                    .isZero();
            assertThat(countOf("fraud_cases", "transfer_id", e.getValue().toString()))
                    .isZero();
        }
        assertThat(redis.opsForValue().get("vel:" + sender + ":1h")).isEqualTo("1");
        assertThat(redis.opsForSet().members("recipients:" + sender)).containsExactly(recipient.toString());
    }

    @Test
    @DisplayName("A failing screening is retried three times, then set aside as dead letter")
    void aFailingScreeningIsRetriedThreeTimesThenDeadLettered() {
        UUID transfer = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        jdbc.update(
                "insert into fraud_cases (transfer_id, sender_id, amount, score, reasons)"
                        + " values (?, ?, 1, 0.5, array['VELOCITY_1H'])",
                transfer.toString(),
                sender.toString());

        sendReserved(eventId, transfer, recipient, "500.00");
        List<ConsumerRecord<String, String>> deadLetters = deadLettersOf(sender.toString());

        assertThat(deadLetters).hasSize(1);
        String key = sender.toString();
        assertThat(failedDeliveries)
                .containsExactly(key + " attempt 1", key + " attempt 2", key + " attempt 3", key + " attempt 4");
        assertThat(countOf("processed_events", "event_id", eventId)).isZero();
    }

    @Test
    @DisplayName("A malformed reservation goes to the dead letters at its first failure")
    void aMalformedReservationIsNotRetried() {
        send("""
                {"id":"%s","data":{"eventType":"FundsReserved","transferId":"%s","senderId":"%s","amount":"20.00","currency":"EUR"}}""".formatted(UUID.randomUUID(), UUID.randomUUID(), sender));

        deadLettersOf(sender.toString());

        assertThat(failedDeliveries).containsExactly(sender + " attempt 1");
    }

    @Test
    @DisplayName("A record with no value goes to the dead letters at its first delivery")
    void aTombstoneIsDeadLetteredAtOnceAndTheNextReservationIsScreened() {
        UUID transfer = UUID.randomUUID();
        int processedBefore = processedEvents();
        int outboxBefore = outboxRows();
        kafka.send(TOPIC, sender.toString(), null).join();
        sendReserved(UUID.randomUUID().toString(), transfer, recipient, "20.00");

        List<ConsumerRecord<String, String>> deadLetters = deadLettersOf(sender.toString());
        awaitResultFor(transfer);

        assertThat(deadLetters).hasSize(1);
        assertThat(deadLetters.getFirst().value()).isNull();
        assertThat(failedDeliveries).containsExactly(sender + " attempt 1");
        assertThat(processedEvents()).isEqualTo(processedBefore + 1);
        assertThat(outboxRows()).isEqualTo(outboxBefore + 1);
        assertThat(outboxEventTypeOf(transfer)).isEqualTo("TransferCleared");
    }

    @Test
    @DisplayName("The same FundsReserved delivered at the same time is screened once")
    void sameEventScreenedConcurrentlyIsScreenedOnce() throws Exception {
        UUID transfer = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        ReservedTransfer reserved =
                new ReservedTransfer(eventId, transfer, sender, recipient, Money.of("500.00", "EUR"));
        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> deliveries = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                deliveries.add(threads.submit(() -> {
                    bothReady.await();
                    screening.screen(reserved);
                    return null;
                }));
            }
            for (Future<?> delivery : deliveries) {
                delivery.get();
            }
        } finally {
            threads.shutdown();
        }

        assertThat(countOf("outbox", "aggregate_id", transfer.toString())).isEqualTo(1);
        assertThat(countOf("fraud_cases", "transfer_id", transfer.toString())).isEqualTo(1);
        assertThat(countOf("processed_events", "event_id", eventId)).isEqualTo(1);
        assertThat(redis.opsForValue().get("vel:" + sender + ":1h")).isEqualTo("1");
        assertThat(redis.opsForSet().members("recipients:" + sender)).containsExactly(recipient.toString());
    }

    @Test
    @DisplayName("The same FundsReserved delivered twice is screened once")
    void sameEventDeliveredTwiceIsScreenedOnce() {
        UUID transfer = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        sendReserved(eventId, transfer, recipient, "500.00");
        sendReserved(eventId, transfer, recipient, "500.00");
        sendReserved(UUID.randomUUID().toString(), next, recipient, "20.00");
        awaitResultFor(next);

        assertThat(countOf("outbox", "aggregate_id", transfer.toString())).isEqualTo(1);
        assertThat(countOf("fraud_cases", "transfer_id", transfer.toString())).isEqualTo(1);
        assertThat(countOf("processed_events", "event_id", eventId)).isEqualTo(1);
        assertThat(redis.opsForValue().get("vel:" + sender + ":1h")).isEqualTo("2");
        assertThat(redis.opsForSet().members("recipients:" + sender)).containsExactly(recipient.toString());
    }

    private List<ConsumerRecord<String, String>> deadLettersOf(String key) {
        Map<String, Object> props = new HashMap<>(consumerFactory.getConfigurationProperties());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.assign(consumer.partitionsFor(DEAD_LETTER_TOPIC).stream()
                    .map(i -> new TopicPartition(DEAD_LETTER_TOPIC, i.partition()))
                    .toList());
            await().atMost(PATIENCE).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
                return !found.isEmpty();
            });
        }
        return found;
    }
}
