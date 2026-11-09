package dev.treyer.sagapay.contracts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventsAsyncApiTest {

    private static final String DOCUMENT = "asyncapi/events.asyncapi.yaml";

    private static final Map<String, Object> DOC = load();

    @Test
    void describesExactlyTheTwoKafkaTopics() {
        Map<String, Object> channels = map(DOC, "channels");

        assertThat(channels.keySet()).containsExactly("payments.transfers", "payments.fraud.results");
        assertThat(map(channels, "payments.transfers").get("address")).isEqualTo("payments.transfers");
        assertThat(map(channels, "payments.fraud.results").get("address")).isEqualTo("payments.fraud.results");
    }

    @Test
    void usesKafkaAsTheOnlyProtocol() {
        Map<String, Object> servers = map(DOC, "servers");

        assertThat(servers.keySet()).containsExactly("kafka");
        assertThat(map(servers, "kafka").get("protocol")).isEqualTo("kafka");
    }

    @Test
    void transfersTopicCarriesTheFiveEventTypes() {
        assertThat(map(map(map(DOC, "channels"), "payments.transfers"), "messages")
                        .keySet())
                .containsExactly(
                        "TransferInitiated", "FundsReserved", "TransferPosted", "TransferBlocked", "TransferFailed");
    }

    @Test
    void fraudResultsTopicCarriesTheTwoResultTypes() {
        assertThat(map(map(map(DOC, "channels"), "payments.fraud.results"), "messages")
                        .keySet())
                .containsExactly("TransferCleared", "TransferFlagged");
    }

    @Test
    void transferBlockedHoldsTheSharedPayloadOfTheTopic() {
        assertThat(dataFields("TransferBlocked"))
                .containsExactly("eventType", "transferId", "senderId", "recipientId", "amount", "currency");
    }

    @Test
    void transferFailedAddsTheReasonToTheSharedPayload() {
        assertThat(dataFields("TransferFailed"))
                .containsExactly("eventType", "transferId", "senderId", "recipientId", "amount", "currency", "reason");
    }

    @ParameterizedTest(name = "TransferBlocked has no {0} field")
    @ValueSource(strings = {"decision", "score", "reasons"})
    void transferBlockedHoldsNoFraudOutcome(String forbidden) {
        assertThat(dataFields("TransferBlocked")).doesNotContain(forbidden);
    }

    @Test
    void fraudResultsCarryTheDecisionTheTransfersTopicDoesNot() {
        assertThat(dataFields("TransferFlagged"))
                .containsExactly("transferId", "senderId", "decision", "score", "reasons", "modelVersion", "ts");
    }

    @Test
    void theFiveOperationsSendOrReceiveOnTheirChannel() {
        Map<String, String> actionAndChannelByOperation = new LinkedHashMap<>();
        map(DOC, "operations").forEach((name, operation) -> {
            Map<String, Object> op = castMap(operation);
            actionAndChannelByOperation.put(
                    name, op.get("action") + " " + map(op, "channel").get("$ref"));
        });

        assertThat(actionAndChannelByOperation)
                .isEqualTo(Map.of(
                        "publishTransferEvents", "send #/channels/payments.transfers",
                        "screenTransfers", "receive #/channels/payments.transfers",
                        "streamTransferActivity", "receive #/channels/payments.transfers",
                        "publishFraudResults", "send #/channels/payments.fraud.results",
                        "applyFraudResults", "receive #/channels/payments.fraud.results"));
    }

    @ParameterizedTest(name = "{0} has type {0}.v1 and its data is {1}")
    @CsvSource({
        "TransferInitiated, TransferEventData",
        "FundsReserved, TransferEventData",
        "TransferPosted, TransferEventData",
        "TransferBlocked, TransferEventData",
        "TransferFailed, TransferFailedData",
        "TransferCleared, FraudResultData",
        "TransferFlagged, FraudResultData"
    })
    void messageHasItsTypeAndItsDataSchema(String message, String dataSchema) {
        Map<String, Object> envelope = envelope(message);
        Map<String, Object> properties = map(envelope, "properties");

        assertThat(map(properties, "type")).isEqualTo(Map.of("const", message + ".v1"));
        assertThat(map(properties, "data")).isEqualTo(Map.of("$ref", "#/components/schemas/" + dataSchema));
    }

    @ParameterizedTest(name = "{0} has the data fields {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "TransferInitiated | eventType,transferId,senderId,recipientId,amount,currency",
                "FundsReserved | eventType,transferId,senderId,recipientId,amount,currency",
                "TransferPosted | eventType,transferId,senderId,recipientId,amount,currency",
                "TransferCleared | transferId,senderId,decision,score,reasons,modelVersion,ts"
            })
    void messageHasItsDataFields(String message, String fields) {
        assertThat(dataFields(message)).containsExactly(fields.split(","));
    }

    @Test
    void theEnvelopeRequiresItsSixFields() {
        assertThat(map(map(DOC, "components"), "schemas").get("CloudEvent"))
                .extracting(schema -> castMap(schema).get("required"))
                .isEqualTo(List.of("id", "source", "type", "time", "subject", "data"));
    }

    @Test
    void eventTypeIsOneOfTheFiveTransferEvents() {
        Map<String, Object> eventType = map(map(schema("TransferEventData"), "properties"), "eventType");

        assertThat(eventType.get("enum"))
                .isEqualTo(List.of(
                        "TransferInitiated", "FundsReserved", "TransferPosted", "TransferBlocked", "TransferFailed"));
        assertThat(schema("TransferEventData").get("required"))
                .isEqualTo(List.of("eventType", "transferId", "senderId", "recipientId", "amount", "currency"));
    }

    @Test
    void failureReasonIsRequiredAndIsOneOfTheFiveCodes() {
        Map<String, Object> extension =
                castMap(((List<?>) schema("TransferFailedData").get("allOf")).get(1));

        assertThat(extension.get("required")).isEqualTo(List.of("reason"));
        assertThat(map(map(extension, "properties"), "reason").get("enum"))
                .isEqualTo(List.of(
                        "INSUFFICIENT_FUNDS",
                        "RESERVE_REJECTED",
                        "POST_REJECTED",
                        "SCREENING_TIMEOUT",
                        "VERIFICATION_EXPIRED"));
    }

    @Test
    void fraudResultRequiresItsSevenFields() {
        assertThat(schema("FraudResultData").get("required"))
                .isEqualTo(List.of("transferId", "senderId", "decision", "score", "reasons", "modelVersion", "ts"));
    }

    @Test
    void fraudDecisionIsClearedOrFlagged() {
        assertThat(fraudProperty("decision"))
                .isEqualTo(Map.of("type", "string", "enum", List.of("CLEARED", "FLAGGED")));
    }

    @Test
    void fraudScoreIsANumberFromZeroToOne() {
        assertThat(fraudProperty("score")).isEqualTo(Map.of("type", "number", "minimum", 0, "maximum", 1));
    }

    @Test
    void fraudReasonsAreAListOfTheThreeCodes() {
        assertThat(fraudProperty("reasons"))
                .isEqualTo(Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of("type", "string", "enum", List.of("VELOCITY", "NEW_RECIPIENT", "AMOUNT_ROUND"))));
    }

    @Test
    void cloudEventPropertiesHaveTheirTypes() {
        Map<String, Object> properties = map(schema("CloudEvent"), "properties");

        assertThat(properties.keySet())
                .containsExactly("id", "source", "type", "time", "subject", "traceparent", "data");
        assertThat(map(properties, "id")).isEqualTo(Map.of("type", "string", "format", "uuid"));
        assertThat(map(properties, "source").get("type")).isEqualTo("string");
        assertThat(map(properties, "type").get("type")).isEqualTo("string");
        assertThat(map(properties, "time")).isEqualTo(Map.of("type", "string", "format", "date-time"));
        assertThat(map(properties, "subject").get("type")).isEqualTo("string");
        assertThat(map(properties, "traceparent").get("type")).isEqualTo(List.of("string", "null"));
        assertThat(map(properties, "data")).isEqualTo(Map.of("type", "object"));
    }

    @Test
    void theGatewayDraftIsGone() {
        assertThat(Path.of(System.getProperty("user.dir"), "openapi-reference", "gateway.openapi.yaml"))
                .doesNotExist();
    }

    private static Map<String, Object> schema(String name) {
        return map(map(map(DOC, "components"), "schemas"), name);
    }

    private static Map<String, Object> fraudProperty(String name) {
        return map(map(schema("FraudResultData"), "properties"), name);
    }

    private static Map<String, Object> envelope(String messageName) {
        Map<String, Object> message = map(map(DOC, "components"), "messages");
        Map<String, Object> payload = resolve(map(message, messageName), "payload");
        return castMap(((List<?>) payload.get("allOf")).get(1));
    }

    private static List<String> dataFields(String messageName) {
        Map<String, Object> envelope = envelope(messageName);
        return propertyNames(resolve(map(envelope, "properties"), "data"));
    }

    private static List<String> propertyNames(Map<String, Object> schema) {
        List<String> names = new ArrayList<>();
        if (schema.get("allOf") instanceof List<?> parts) {
            for (Object part : parts) {
                names.addAll(propertyNames(resolveNode(castMap(part))));
            }
        }
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            properties.keySet().forEach(key -> names.add((String) key));
        }
        return names;
    }

    private static Map<String, Object> resolve(Map<String, Object> parent, String key) {
        return resolveNode(map(parent, key));
    }

    private static Map<String, Object> resolveNode(Map<String, Object> node) {
        if (node.get("$ref") instanceof String ref) {
            Map<String, Object> target = DOC;
            for (String step : ref.substring("#/".length()).split("/")) {
                target = map(target, step);
            }
            return target;
        }
        return node;
    }

    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return castMap(parent.get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> load() {
        try (InputStream in = EventsAsyncApiTest.class.getClassLoader().getResourceAsStream(DOCUMENT)) {
            assertThat(in).as(DOCUMENT).isNotNull();
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
