package dev.treyer.sagapay.orchestrator.adapter.out.messaging;

import dev.treyer.sagapay.orchestrator.application.port.out.OutboxRelayPort;
import dev.treyer.sagapay.orchestrator.config.OutboxProperties;
import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What a run does when a row cannot be sent; the queries themselves are tested on a real database. */
class OutboxPollerFailureTest {

    private final OutboxRelayPort outbox = mock(OutboxRelayPort.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final OutboxPoller poller =
            new OutboxPoller(outbox, kafka, JsonMapper.builder().build(), Tracer.NOOP, new OutboxProperties(500, 100));

    private static final String KEY_1 = "key-1";
    private static final String KEY_2 = "key-2";
    private static final String KEY_3 = "key-3";

    private static OutboxRow row(String key) {
        return row(key, "{}");
    }

    private static OutboxRow row(String key, String payload) {
        return new OutboxRow(
                UUID.randomUUID(),
                "Transfer",
                UUID.randomUUID(),
                key,
                "TransferInitiated",
                payload,
                null,
                Instant.now());
    }

    private static ArgumentMatcher<ProducerRecord<String, String>> keyedBy(String key) {
        return sent -> sent != null && key.equals(sent.key());
    }

    @SuppressWarnings("unchecked")
    private void sendsSucceed() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));
    }

    private void sendFailsFor(String key, Exception failure) {
        when(kafka.send(argThat(keyedBy(key)))).thenReturn(CompletableFuture.failedFuture(failure));
    }

    private void rows(OutboxRow... rows) {
        when(outbox.findPublishable(100)).thenReturn(List.of(rows));
    }

    private static UUID idOf(OutboxRow row) {
        return row.getId();
    }

    @BeforeEach
    void thisInstanceHoldsTheLock() {
        when(outbox.tryLockPublisher()).thenReturn(true);
        sendsSucceed();
    }

    @Test
    @SuppressWarnings("unchecked")
    @Tag("M3-S55")
    @DisplayName("Only one instance publishes at a time")
    void aRunWithoutTheLockReadsAndSendsNothing() {
        when(outbox.tryLockPublisher()).thenReturn(false);

        poller.drain();

        verify(outbox, never()).findPublishable(100);
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    @Tag("M3-S53")
    @DisplayName("A Kafka outage quarantines nothing and keeps the order")
    void aTemporaryFailureStopsTheRunKeepsWhatWasSentAndQuarantinesNothing() {
        OutboxRow sent = row(KEY_1);
        OutboxRow failing = row(KEY_2);
        OutboxRow after = row(KEY_3);
        rows(sent, failing, after);
        sendFailsFor(KEY_2, new IllegalStateException("broker down"));

        assertThatCode(poller::drain).doesNotThrowAnyException();

        verify(outbox).markPublished(idOf(sent));
        verify(outbox, never()).markPublished(idOf(failing));
        verify(outbox, never()).markPublished(idOf(after));
        verify(outbox, never()).quarantine(any(), any(), any());
        verify(kafka, never()).send(argThat(keyedBy(KEY_3)));
    }

    @Test
    @Tag("M3-S52")
    @DisplayName("A row that cannot be published blocks only its own key")
    void aMessageTooLargeQuarantinesItsKeyAndTheOtherKeysGoOn() {
        OutboxRow tooLarge = row(KEY_1);
        OutboxRow sameKey = row(KEY_1);
        OutboxRow otherKey = row(KEY_2);
        rows(tooLarge, sameKey, otherKey);
        sendFailsFor(KEY_1, new KafkaProducerException(null, "send failed", new RecordTooLargeException("too large")));

        assertThatCode(poller::drain).doesNotThrowAnyException();

        verify(outbox).quarantine(eq(KEY_1), eq(idOf(tooLarge)), contains("RecordTooLargeException"));
        verify(kafka, times(1)).send(argThat(keyedBy(KEY_1)));
        verify(outbox, never()).markPublished(idOf(tooLarge));
        verify(outbox, never()).markPublished(idOf(sameKey));
        verify(outbox).markPublished(idOf(otherKey));
    }

    @Test
    @Tag("M3-S52")
    @DisplayName("A row that cannot be published blocks only its own key")
    @SuppressWarnings("unchecked")
    void aPayloadThatIsNotJsonQuarantinesItsKeyWithoutSendingIt() {
        OutboxRow malformed = row(KEY_1, "not json");
        OutboxRow otherKey = row(KEY_2);
        rows(malformed, otherKey);

        assertThatCode(poller::drain).doesNotThrowAnyException();

        verify(outbox).quarantine(eq(KEY_1), eq(idOf(malformed)), any());
        verify(kafka, times(1)).send(any(ProducerRecord.class));
        verify(outbox).markPublished(idOf(otherKey));
    }

    @Test
    void theRowsOfAKeyAreSentInTheOrderTheyWereRead() {
        OutboxRow first = row(KEY_1);
        OutboxRow second = row(KEY_1);
        rows(first, second);

        poller.drain();

        var order = inOrder(outbox);
        order.verify(outbox).markPublished(idOf(first));
        order.verify(outbox).markPublished(idOf(second));
    }
}
