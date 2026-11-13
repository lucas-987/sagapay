package dev.treyer.sagapay.orchestrator.adapter.in.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.treyer.sagapay.orchestrator.domain.TransferEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityStreamListenerTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRANSFER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SENDER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RECIPIENT = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private final List<TransferEvent> relayed = new ArrayList<>();
    private final ActivityStreamListener listener =
            new ActivityStreamListener(relayed::add, JsonMapper.builder().build());
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger listenerLogger = (Logger) LoggerFactory.getLogger(ActivityStreamListener.class);

    @BeforeEach
    void captureLogs() {
        logs.start();
        listenerLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        listenerLogger.detachAppender(logs);
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("payments.transfers", 0, 7L, SENDER.toString(), value);
    }

    /** The envelope the outbox poller publishes; a null argument leaves its field out. */
    private static String event(String id, String type, String data) {
        List<String> fields = new ArrayList<>();
        if (id != null) {
            fields.add("\"id\":\"" + id + "\"");
        }
        fields.add("\"source\":\"transfer-orchestrator\"");
        if (type != null) {
            fields.add("\"type\":\"" + type + "\"");
        }
        fields.add("\"time\":\"2026-11-01T10:00:00Z\",\"subject\":\"" + TRANSFER_ID + "\",\"traceparent\":null");
        if (data != null) {
            fields.add("\"data\":" + data);
        }
        return "{" + String.join(",", fields) + "}";
    }

    private static String data(String transferId, String senderId, String recipientId) {
        return data(transferId, senderId, recipientId, "\"80.0000\"", "\"EUR\"");
    }

    /** A null amount or currency leaves the field out. */
    private static String data(String transferId, String senderId, String recipientId, String amount, String currency) {
        List<String> fields = new ArrayList<>();
        if (transferId != null) {
            fields.add("\"transferId\":\"" + transferId + "\"");
        }
        if (senderId != null) {
            fields.add("\"senderId\":\"" + senderId + "\"");
        }
        if (recipientId != null) {
            fields.add("\"recipientId\":\"" + recipientId + "\"");
        }
        if (amount != null) {
            fields.add("\"amount\":" + amount);
        }
        if (currency != null) {
            fields.add("\"currency\":" + currency);
        }
        return "{" + String.join(",", fields) + "}";
    }

    static Stream<Arguments> unusableRecords() {
        String id = EVENT_ID.toString();
        String ok = data(TRANSFER_ID.toString(), SENDER.toString(), RECIPIENT.toString());
        String t = TRANSFER_ID.toString();
        String s = SENDER.toString();
        String r = RECIPIENT.toString();
        String lacking = "record lacks a field the stream needs";
        return Stream.of(
                Arguments.argumentSet("no value", null, "record has no value"),
                Arguments.argumentSet("not json", "not json {", "record is not readable"),
                Arguments.argumentSet("a json null", "null", "record is not an event"),
                Arguments.argumentSet(
                        "an event id that is not a uuid",
                        event("abc", "TransferPosted.v1", ok),
                        "record is not readable"),
                Arguments.argumentSet("no event id", event(null, "TransferPosted.v1", ok), lacking),
                Arguments.argumentSet("no type", event(id, null, ok), lacking),
                Arguments.argumentSet("no data", event(id, "TransferPosted.v1", null), lacking),
                Arguments.argumentSet("no transfer id", event(id, "TransferPosted.v1", data(null, s, r)), lacking),
                Arguments.argumentSet("no sender", event(id, "TransferPosted.v1", data(t, null, r)), lacking),
                Arguments.argumentSet("no recipient", event(id, "TransferPosted.v1", data(t, s, null)), lacking),
                Arguments.argumentSet(
                        "no amount", event(id, "TransferPosted.v1", data(t, s, r, null, "\"EUR\"")), lacking),
                Arguments.argumentSet(
                        "no currency", event(id, "TransferPosted.v1", data(t, s, r, "\"80.0000\"", null)), lacking));
    }

    @Test
    @DisplayName("A failed transfer event is handed on with its type, its parties, its amount and its reason")
    void aReadableEventIsHandedOn() {
        String value = event(
                EVENT_ID.toString(),
                "TransferFailed.v1",
                "{\"transferId\":\"%s\",\"senderId\":\"%s\",\"recipientId\":\"%s\",\"amount\":\"80.0000\",\"currency\":\"EUR\",\"eventType\":\"TransferFailed\",\"reason\":\"INSUFFICIENT_FUNDS\"}"
                        .formatted(TRANSFER_ID, SENDER, RECIPIENT));

        listener.onTransferEvent(record(value));

        assertThat(relayed)
                .containsExactly(new TransferEvent(
                        EVENT_ID,
                        "TransferFailed.v1",
                        TRANSFER_ID,
                        SENDER,
                        RECIPIENT,
                        "80.0000",
                        "EUR",
                        "INSUFFICIENT_FUNDS"));
        assertThat(logs.list).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("unusableRecords")
    @DisplayName("A record that is unreadable or lacks a field is skipped with a warning")
    void anUnusableRecordIsSkippedWithAWarning(String value, String reason) {
        listener.onTransferEvent(record(value));

        assertThat(relayed).isEmpty();
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logs.list.get(0).getMessage()).isEqualTo("activity stream skipped a record");
        // The cause, when there is one, is the last argument and is not a field.
        assertThat(Arrays.stream(logs.list.get(0).getArgumentArray())
                        .filter(argument -> argument != null && !(argument instanceof Throwable))
                        .map(Object::toString))
                .containsExactly(
                        "messaging.destination.name=payments.transfers",
                        "messaging.destination.partition.id=0",
                        "messaging.kafka.offset=7",
                        "reason=" + reason);
    }
}
