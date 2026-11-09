package dev.treyer.sagapay.orchestrator.contract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityStreamContractTest {

    private static final String DOCUMENT = "openapi/activity-stream.openapi.yaml";

    private static final Map<String, Object> DOC = load();

    @Test
    void describesOnlyTheGetOfTheActivityStream() {
        Map<String, Object> paths = map(DOC, "paths");

        assertThat(paths.keySet()).containsExactly("/v1/stream/activity");
        assertThat(map(paths, "/v1/stream/activity").keySet()).containsExactly("get");
    }

    @Test
    void requiresABearerToken() {
        Map<String, Object> securitySchemes = map(map(DOC, "components"), "securitySchemes");

        assertThat(DOC.get("security")).isEqualTo(List.of(Map.of("bearer", List.of())));
        assertThat(map(securitySchemes, "bearer"))
                .isEqualTo(Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"));
    }

    @Test
    void answers200AsAnEventStreamAnd401WithoutAValidToken() {
        Map<String, Object> responses = map(get(), "responses");

        assertThat(responses.keySet()).containsExactly("200", "401");
        assertThat(map(map(responses, "200"), "content").keySet()).containsExactly("text/event-stream");
    }

    @Test
    void declaresNoLastEventIdHeader() {
        assertThat(get().get("parameters")).isNull();
    }

    @Test
    void streamsExactlyTheThreeEventsWithTheirData() {
        Map<String, List<String>> dataFieldsByEvent = new LinkedHashMap<>();
        for (Map<String, Object> event : eventSchemas()) {
            Map<String, Object> properties = map(event, "properties");
            dataFieldsByEvent.put(
                    (String) map(properties, "event").get("const"),
                    new ArrayList<>(
                            map(resolve(map(properties, "data")), "properties").keySet()));
        }

        assertThat(dataFieldsByEvent)
                .isEqualTo(Map.of(
                        "transfer.posted",
                        List.of("transferId", "senderId", "recipientId", "amount", "currency"),
                        "transfer.blocked",
                        List.of("transferId", "senderId", "recipientId", "amount", "currency"),
                        "transfer.failed",
                        List.of("transferId", "senderId", "recipientId", "amount", "currency", "cause")));
    }

    @Test
    void everyDataFieldIsRequiredExceptTheCauseOfTheFailedEvent() {
        List<String> required = List.of("transferId", "senderId", "recipientId", "amount", "currency");

        assertThat(dataSchemaOf("transfer.posted").get("required")).isEqualTo(required);
        assertThat(dataSchemaOf("transfer.blocked").get("required")).isEqualTo(required);
        assertThat(dataSchemaOf("transfer.failed").get("required")).isEqualTo(required);
    }

    @Test
    void everyEventRequiresItsNameItsIdAndItsData() {
        List<List<?>> requiredByEvent = new ArrayList<>();
        for (Map<String, Object> event : eventSchemas()) {
            requiredByEvent.add((List<?>) event.get("required"));
        }

        assertThat(requiredByEvent)
                .containsExactly(
                        List.of("event", "id", "data"), List.of("event", "id", "data"), List.of("event", "id", "data"));
    }

    @Test
    void everyEventIdIsTheUuidOfTheKafkaEvent() {
        for (Map<String, Object> event : eventSchemas()) {
            assertThat(map(map(event, "properties"), "id")).isEqualTo(Map.of("$ref", "#/components/schemas/EventId"));
        }
        assertThat(map(map(DOC, "components"), "schemas").get("EventId"))
                .isEqualTo(Map.of(
                        "type",
                        "string",
                        "format",
                        "uuid",
                        "description",
                        "Id of the Kafka event (CloudEvent) the stream event comes from."));
    }

    @Test
    void statesThatTheCauseGoesToTheSenderOnly() {
        Map<String, Object> cause = map(map(dataSchemaOf("transfer.failed"), "properties"), "cause");

        assertThat((String) cause.get("description")).contains("Sent to the sender only and absent for the recipient");
    }

    @ParameterizedTest(name = "no schema has a {0} field")
    @ValueSource(strings = {"balance", "available", "decision", "score", "reasons"})
    void carriesNoBalanceNorFraudOutcome(String forbidden) {
        List<String> fields = new ArrayList<>();
        for (Object schema : map(map(DOC, "components"), "schemas").values()) {
            fields.addAll(propertyNames(schema));
        }

        assertThat(fields).isNotEmpty().doesNotContain(forbidden);
    }

    @Test
    void noGeneratorExecutionReadsTheStreamDocument() throws Exception {
        Document pom = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(Path.of(System.getProperty("user.dir"), "pom.xml").toFile());
        List<String> inputSpecs = new ArrayList<>();
        NodeList plugins = pom.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            if (!"openapi-generator-maven-plugin".equals(text(plugin, "artifactId"))) {
                continue;
            }
            NodeList specs = plugin.getElementsByTagName("inputSpec");
            for (int j = 0; j < specs.getLength(); j++) {
                inputSpecs.add(specs.item(j).getTextContent().trim());
            }
        }

        assertThat(inputSpecs)
                .isEqualTo(List.of("${project.basedir}/src/main/resources/openapi/transfer-service.openapi.yaml"));
    }

    private static String text(Element parent, String child) {
        NodeList nodes = parent.getElementsByTagName(child);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    private static List<Map<String, Object>> eventSchemas() {
        Map<String, Object> schema = map(map(map(get(), "responses"), "200"), "content");
        Map<String, Object> eventStream = map(schema, "text/event-stream");
        List<Map<String, Object>> events = new ArrayList<>();
        for (Object ref : (List<?>) map(eventStream, "schema").get("oneOf")) {
            events.add(resolve(castMap(ref)));
        }
        return events;
    }

    private static Map<String, Object> dataSchemaOf(String eventName) {
        for (Map<String, Object> event : eventSchemas()) {
            Map<String, Object> properties = map(event, "properties");
            if (eventName.equals(map(properties, "event").get("const"))) {
                return resolve(map(properties, "data"));
            }
        }
        throw new AssertionError("no event " + eventName);
    }

    private static List<String> propertyNames(Object node) {
        List<String> names = new ArrayList<>();
        if (!(node instanceof Map<?, ?> schema)) {
            return names;
        }
        if (schema.get("$ref") instanceof String) {
            return names;
        }
        if (schema.get("properties") instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> entry : props.entrySet()) {
                names.add((String) entry.getKey());
                names.addAll(propertyNames(entry.getValue()));
            }
        }
        for (String combinator : List.of("allOf", "oneOf", "anyOf")) {
            if (schema.get(combinator) instanceof List<?> parts) {
                parts.forEach(part -> names.addAll(propertyNames(part)));
            }
        }
        names.addAll(propertyNames(schema.get("items")));
        return names;
    }

    private static Map<String, Object> get() {
        return map(map(map(DOC, "paths"), "/v1/stream/activity"), "get");
    }

    private static Map<String, Object> resolve(Map<String, Object> node) {
        Object ref = node.get("$ref");
        if (ref == null) {
            return node;
        }
        String name = ((String) ref).substring("#/components/schemas/".length());
        return map(map(map(DOC, "components"), "schemas"), name);
    }

    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return castMap(parent.get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> load() {
        try (InputStream in = ActivityStreamContractTest.class.getClassLoader().getResourceAsStream(DOCUMENT)) {
            assertThat(in).as(DOCUMENT).isNotNull();
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
