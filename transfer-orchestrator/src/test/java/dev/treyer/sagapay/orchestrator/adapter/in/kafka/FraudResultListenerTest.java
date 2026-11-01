package dev.treyer.sagapay.orchestrator.adapter.in.kafka;

import dev.treyer.sagapay.orchestrator.domain.FraudDecision;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudResultListenerTest {

    private static final String EVENT_ID = "6f0c5b8e-5d0e-4a39-9a1e-3f1d2b7c9a10";
    private static final String TRANSFER_ID = "0b7f2a4c-1c53-4a3b-8c55-6a5a1d9f7e21";

    private final List<FraudResult> applied = new ArrayList<>();
    private final FraudResultListener listener =
            new FraudResultListener(applied::add, JsonMapper.builder().build());

    /** A null argument leaves its field out. */
    private static String data(String transferId, String decision, String score, String reasons) {
        List<String> fields = new ArrayList<>();
        if (transferId != null) {
            fields.add("\"transferId\":\"" + transferId + "\"");
        }
        if (decision != null) {
            fields.add("\"decision\":\"" + decision + "\"");
        }
        if (score != null) {
            fields.add("\"score\":" + score);
        }
        if (reasons != null) {
            fields.add("\"reasons\":" + reasons);
        }
        return "{" + String.join(",", fields) + "}";
    }

    private static String event(String id, String data) {
        return "{\"id\":" + (id == null ? "null" : "\"" + id + "\"")
                + ",\"source\":\"fraud-detection-service\",\"type\":\"TransferCleared.v1\","
                + "\"time\":\"2026-11-01T10:00:00Z\",\"subject\":\"" + TRANSFER_ID + "\",\"traceparent\":null,"
                + "\"data\":" + data + "}";
    }

    @Test
    void aClearedResultIsHandedToTheUseCaseWithItsEventId() {
        listener.onFraudResult(event(
                EVENT_ID,
                "{\"transferId\":\"" + TRANSFER_ID + "\",\"senderId\":\"" + UUID.randomUUID() + "\","
                        + "\"decision\":\"CLEARED\",\"score\":0.1500,\"reasons\":[\"AMOUNT_ROUND\",\"NEW_RECIPIENT\"],"
                        + "\"modelVersion\":\"v1\",\"ts\":\"2026-11-01T10:00:00Z\"}"));

        assertThat(applied)
                .containsExactly(new FraudResult(
                        UUID.fromString(EVENT_ID),
                        UUID.fromString(TRANSFER_ID),
                        FraudDecision.CLEARED,
                        new BigDecimal("0.1500"),
                        List.of("AMOUNT_ROUND", "NEW_RECIPIENT")));
    }

    @Test
    void aFlaggedResultIsHandedToTheUseCaseToo() {
        listener.onFraudResult(event(EVENT_ID, data(TRANSFER_ID, "FLAGGED", "0.9000", "[\"VELOCITY_1H\"]")));

        assertThat(applied)
                .containsExactly(new FraudResult(
                        UUID.fromString(EVENT_ID),
                        UUID.fromString(TRANSFER_ID),
                        FraudDecision.FLAGGED,
                        new BigDecimal("0.9000"),
                        List.of("VELOCITY_1H")));
    }

    @Test
    void aClearedResultWithNoReasonsKeepsAnEmptyList() {
        listener.onFraudResult(event(EVENT_ID, data(TRANSFER_ID, "CLEARED", "0", "[]")));

        assertThat(applied)
                .containsExactly(new FraudResult(
                        UUID.fromString(EVENT_ID),
                        UUID.fromString(TRANSFER_ID),
                        FraudDecision.CLEARED,
                        BigDecimal.ZERO,
                        List.of()));
    }

    static Stream<Arguments> malformedRecords() {
        String validData = data(TRANSFER_ID, "CLEARED", "0.1", "[]");
        return Stream.of(
                Arguments.of("text that is not JSON", "not json {", "fraud result is not readable"),
                Arguments.of("empty record", "", "fraud result is not readable"),
                Arguments.of("event id that is not a UUID", event("abc", validData), "fraud result is not readable"),
                Arguments.of(
                        "unknown decision",
                        event(EVENT_ID, data(TRANSFER_ID, "MAYBE", "0.1", "[]")),
                        "fraud result is not readable"),
                Arguments.of("JSON null", "null", "fraud result has no id"),
                Arguments.of("no event id", event(null, validData), "fraud result has no id"),
                Arguments.of("no data", "{\"id\":\"" + EVENT_ID + "\"}", "fraud result has no data"),
                Arguments.of(
                        "no transferId",
                        event(EVENT_ID, data(null, "CLEARED", "0.1", "[]")),
                        "fraud result has no transferId"),
                Arguments.of(
                        "no decision",
                        event(EVENT_ID, data(TRANSFER_ID, null, "0.1", "[]")),
                        "fraud result has no decision"),
                Arguments.of(
                        "no score",
                        event(EVENT_ID, data(TRANSFER_ID, "CLEARED", null, "[]")),
                        "fraud result has no score"),
                Arguments.of(
                        "no reasons",
                        event(EVENT_ID, data(TRANSFER_ID, "CLEARED", "0.1", null)),
                        "fraud result has no reasons"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedRecords")
    void aMalformedRecordIsRefusedWithoutReachingTheUseCase(String name, String record, String message) {
        assertThatThrownBy(() -> listener.onFraudResult(record))
                .isExactlyInstanceOf(MalformedFraudResultException.class)
                .hasMessage(message);
        assertThat(applied).isEmpty();
    }
}
