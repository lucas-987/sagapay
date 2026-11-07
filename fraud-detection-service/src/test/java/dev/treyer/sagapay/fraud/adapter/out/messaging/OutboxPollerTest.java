package dev.treyer.sagapay.fraud.adapter.out.messaging;

import dev.treyer.sagapay.fraud.FraudResultsTopic;
import dev.treyer.sagapay.fraud.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// The poll interval is an hour (the first run waits one interval too) so that the scheduled run
// never competes with the explicit drain() calls below.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "outbox.poll-interval-ms=3600000")
class OutboxPollerTest {

    @Autowired
    private OutboxPoller poller;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    @Autowired
    private MeterRegistry meters;

    // Raw: the bean is declared as KafkaTemplate<?, ?>, which a parameterised field does not match.
    @SuppressWarnings("rawtypes")
    @MockitoSpyBean
    private KafkaTemplate kafka;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    // The outbox is shared by the tests of this class: start each one with nothing left to send.
    @BeforeEach
    void startWithAnEmptyOutbox() {
        jdbc.update("delete from outbox_quarantine");
        jdbc.update("update outbox set published_at = now() where published_at is null");
    }

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);

    private UUID insertRowCreatedAt(OffsetDateTime createdAt, UUID transfer) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into outbox (id, aggregate_type, aggregate_id, event_type, payload, created_at)"
                        + " values (?, 'Transfer', ?, 'TransferCleared', cast(? as jsonb), ?)",
                id,
                transfer,
                "{\"transferId\":\"" + transfer + "\"}",
                createdAt);
        return id;
    }

    private static ProducerRecord<String, String> recordKeyedBy(UUID transfer) {
        return argThat((ProducerRecord<String, String> r) -> transfer.toString().equals(r.key()));
    }

    private UUID insertRow(UUID id, UUID transfer, String eventType, String payload) {
        jdbc.update(
                "insert into outbox (id, aggregate_type, aggregate_id, event_type, payload)"
                        + " values (?, 'Transfer', ?, ?, cast(? as jsonb))",
                id,
                transfer,
                eventType,
                payload);
        return id;
    }

    private List<ConsumerRecord<String, String>> recordsOf(UUID transfer) {
        return FraudResultsTopic.recordsKeyedBy(consumerFactory, transfer.toString());
    }

    private boolean isPublished(UUID rowId) {
        return jdbc.queryForObject("select published_at is not null from outbox where id = ?", Boolean.class, rowId);
    }

    @Test
    @Tag("M3-S19")
    @DisplayName("A fraud result republished after a crash keeps its identity")
    void aResultPublishedTwiceKeepsTheIdentityOfItsRow() {
        UUID transfer = UUID.randomUUID();
        UUID rowId = insertRow(UUID.randomUUID(), transfer, "TransferCleared", "{\"transferId\":\"" + transfer + "\"}");
        poller.drain();
        jdbc.update("update outbox set published_at = null where id = ?", rowId);

        poller.drain();

        List<ConsumerRecord<String, String>> records = recordsOf(transfer);
        assertThat(records).hasSize(2);
        assertThat(records.stream()
                        .map(r -> jsonMapper.readTree(r.value()).get("id").asString()))
                .containsExactly(rowId.toString(), rowId.toString());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"TransferCleared,CLEARED", "TransferFlagged,FLAGGED"})
    void aRowIsPublishedAsACloudEventKeyedByTheTransfer(String eventType, String decision) {
        UUID transfer = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID rowId = insertRow(
                UUID.randomUUID(),
                transfer,
                eventType,
                "{\"transferId\":\"%s\",\"senderId\":\"%s\",\"decision\":\"%s\",\"score\":0.7,\"reasons\":[\"NEW_RECIPIENT\"],\"modelVersion\":\"rules-v1\",\"ts\":\"2026-03-01T10:15:30Z\"}"
                        .formatted(transfer, sender, decision));

        poller.drain();

        List<ConsumerRecord<String, String>> records = recordsOf(transfer);
        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> record = records.getFirst();
        assertThat(record.key()).isEqualTo(transfer.toString());
        // The envelope time is drawn at each publish: it is the only field left out of the comparison.
        ObjectNode event = (ObjectNode) jsonMapper.readTree(record.value());
        assertThat(event.remove("time")).isNotNull();
        assertThat(event)
                .isEqualTo(jsonMapper.readTree(
                        "{\"id\":\"%s\",\"source\":\"fraud-detection-service\",\"type\":\"%s.v1\",\"subject\":\"%s\",\"traceparent\":null,\"data\":{\"transferId\":\"%s\",\"senderId\":\"%s\",\"decision\":\"%s\",\"score\":0.7,\"reasons\":[\"NEW_RECIPIENT\"],\"modelVersion\":\"rules-v1\",\"ts\":\"2026-03-01T10:15:30Z\"}}"
                                .formatted(rowId, eventType, transfer, transfer, sender, decision)));
        assertThat(isPublished(rowId)).isTrue();
    }

    @Test
    void theTraceContextTravelsInATraceparentHeaderNotInThePayload() {
        UUID transfer = UUID.randomUUID();
        insertRow(UUID.randomUUID(), transfer, "TransferCleared", "{\"transferId\":\"" + transfer + "\"}");

        poller.drain();

        ConsumerRecord<String, String> record = recordsOf(transfer).getFirst();
        String traceparent =
                new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8);
        assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
        assertThat(jsonMapper.readTree(record.value()).get("traceparent").isNull())
                .isTrue();
    }

    @Test
    void aRowAlreadyPublishedIsNotPublishedAgain() {
        UUID transfer = UUID.randomUUID();
        insertRow(UUID.randomUUID(), transfer, "TransferCleared", "{\"transferId\":\"" + transfer + "\"}");
        poller.drain();

        poller.drain();

        assertThat(recordsOf(transfer)).hasSize(1);
    }

    @Test
    void aDrainWithNothingToSendSendsNothing() {
        poller.drain();

        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void aDrainSendsAtMostOneHundredRowsAndTheNextOneTakesTheRest() {
        jdbc.update("""
                insert into outbox (aggregate_type, aggregate_id, event_type, payload, created_at)
                select 'Transfer', gen_random_uuid(), 'TransferCleared', '{}'::jsonb,
                       timestamptz '2026-04-01 10:00:00+00' + n * interval '1 second'
                from generate_series(1, 101) n
                """);

        poller.drain();

        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox where published_at is not null and created_at between timestamptz '2026-04-01 00:00:00+00' and timestamptz '2026-04-02 00:00:00+00'",
                        Integer.class))
                .isEqualTo(100);
        assertThat(jdbc.queryForList(
                        "select to_char(created_at at time zone 'UTC', 'HH24:MI:SS') from outbox where published_at is null",
                        String.class))
                .containsExactly("10:01:41");

        poller.drain();

        assertThat(jdbc.queryForObject("select count(*) from outbox where published_at is null", Integer.class))
                .isZero();
    }

    @Test
    void rowsAreSentOldestFirst() {
        UUID oldest = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        insertRowCreatedAt(T0.plusSeconds(2), middle);
        insertRowCreatedAt(T0.plusSeconds(3), newest);
        insertRowCreatedAt(T0.plusSeconds(1), oldest);

        poller.drain();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(3)).send(sent.capture());
        assertThat(sent.getAllValues().stream().map(ProducerRecord::key))
                .containsExactly(oldest.toString(), middle.toString(), newest.toString());
    }

    @Test
    @Tag("M3-S53")
    @DisplayName("A Kafka outage quarantines nothing and keeps the order")
    void aFailedSendKeepsTheRowsAlreadySentAndTheNextDrainResumesAtTheFailedOne() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID firstRow = insertRowCreatedAt(T0, first);
        UUID secondRow = insertRowCreatedAt(T0.plusSeconds(1), second);
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .doCallRealMethod()
                .when(kafka)
                .send(recordKeyedBy(second));

        poller.drain();

        assertThat(isPublished(firstRow)).isTrue();
        assertThat(isPublished(secondRow)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from outbox_quarantine", Integer.class))
                .isZero();

        poller.drain();

        assertThat(isPublished(secondRow)).isTrue();
        assertThat(recordsOf(first)).hasSize(1);
        assertThat(recordsOf(second)).hasSize(1);
    }

    @Test
    @Tag("M3-S52")
    @DisplayName("A row that cannot be published blocks only its own key")
    void aQuarantinedKeyStaysOutOfTheQueriesAndTheMetricsCountIt() {
        UUID stuck = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID stuckRow = insertRowCreatedAt(T0, stuck);
        UUID otherRow = insertRowCreatedAt(T0.plusSeconds(1), other);
        doReturn(CompletableFuture.failedFuture(new RecordTooLargeException("too large")))
                .when(kafka)
                .send(recordKeyedBy(stuck));

        poller.drain();
        poller.drain();

        assertThat(isPublished(stuckRow)).isFalse();
        assertThat(isPublished(otherRow)).isTrue();
        assertThat(jdbc.queryForObject(
                        "select outbox_id from outbox_quarantine where aggregate_id = ?", UUID.class, stuck))
                .isEqualTo(stuckRow);
        verify(kafka, times(1)).send(recordKeyedBy(stuck));
        assertThat(meters.get("outbox.quarantined.keys").gauge().value()).isEqualTo(1);
        assertThat(meters.get("outbox.oldest.unpublished.age").gauge().value()).isZero();
    }

    @Test
    @Tag("M3-S54")
    @DisplayName("A key released from quarantine publishes in order")
    void aKeyDeletedFromTheQuarantinePublishesItsRowsInOrder() {
        UUID key = UUID.randomUUID();
        UUID firstRow = insertRowCreatedAt(T0, key);
        UUID secondRow = insertRowCreatedAt(T0.plusSeconds(1), key);
        doReturn(CompletableFuture.failedFuture(new RecordTooLargeException("too large")))
                .doCallRealMethod()
                .when(kafka)
                .send(recordKeyedBy(key));
        poller.drain();

        jdbc.update("delete from outbox_quarantine where aggregate_id = ?", key);
        poller.drain();

        assertThat(recordsOf(key).stream()
                        .map(r -> jsonMapper.readTree(r.value()).get("id").asString()))
                .containsExactly(firstRow.toString(), secondRow.toString());
    }

    @Test
    void theAgeOfTheOutboxIsThatOfItsOldestUnpublishedRow() {
        insertRowCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1), UUID.randomUUID());

        assertThat(meters.get("outbox.oldest.unpublished.age").gauge().value()).isGreaterThanOrEqualTo(3600);
    }

    @Test
    @Tag("M3-S55")
    @DisplayName("Only one instance publishes at a time")
    void aDrainWhileAnotherOnePublishesLeavesEverythingToItWithoutWaiting() throws Exception {
        UUID[] transfers = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        for (int i = 0; i < transfers.length; i++) {
            insertRowCreatedAt(T0.plusSeconds(i), transfers[i]);
        }
        // The first drain holds the lock while its first send waits for the second drain to finish.
        CountDownLatch firstIsSending = new CountDownLatch(1);
        CountDownLatch secondIsDone = new CountDownLatch(1);
        doAnswer(invocation -> {
                    firstIsSending.countDown();
                    secondIsDone.await(60, TimeUnit.SECONDS);
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(kafka)
                .send(any(ProducerRecord.class));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(poller::drain);
            assertThat(firstIsSending.await(60, TimeUnit.SECONDS)).isTrue();

            Future<?> second = pool.submit(poller::drain);

            assertThatCode(() -> second.get(30, TimeUnit.SECONDS)).doesNotThrowAnyException();
            secondIsDone.countDown();
            first.get(60, TimeUnit.SECONDS);
        } finally {
            secondIsDone.countDown();
            pool.shutdown();
        }

        for (UUID transfer : transfers) {
            assertThat(recordsOf(transfer)).hasSize(1);
        }
    }
}
