package dev.treyer.sagapay.fraud.adapter.out.messaging;

import dev.treyer.sagapay.fraud.domain.OutboxMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class FraudResultEventJsonTest {

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void aFlaggedResultIsPublishedWithTheEnvelopeAndPayloadFieldsOfTheEventsPage() {
        UUID rowId = UUID.fromString("0b1a7c52-7d5e-4a39-9f0e-2f6c1d8e4a10");
        String transfer = "6f0c6a1e-3b7a-4a88-8c11-5d9a1e0b7c22";
        String sender = "a3e9b6d4-1c2f-4e7a-b5d8-90c1f2e3a4b5";
        String payload = """
                {"transferId":"%s","senderId":"%s","decision":"FLAGGED","score":0.7000,\
                "reasons":["NEW_RECIPIENT","AMOUNT_ROUND"],"modelVersion":"rules-v1",\
                "ts":"2026-03-01T10:15:30Z"}""".formatted(transfer, sender);

        String json = jsonMapper.writeValueAsString(FraudResultEvents.of(
                new OutboxMessage(rowId, "Transfer", UUID.fromString(transfer), "TransferFlagged", payload),
                jsonMapper));

        ObjectNode event = (ObjectNode) jsonMapper.readTree(json);
        JsonNode time = event.remove("time");
        assertThat(Instant.parse(time.asString())).isNotNull();
        assertThat(event).isEqualTo(jsonMapper.readTree("""
                        {"id":"0b1a7c52-7d5e-4a39-9f0e-2f6c1d8e4a10","source":"fraud-detection-service",\
                        "type":"TransferFlagged.v1","subject":"%s","traceparent":null,\
                        "data":{"transferId":"%s","senderId":"%s","decision":"FLAGGED","score":0.7000,\
                        "reasons":["NEW_RECIPIENT","AMOUNT_ROUND"],"modelVersion":"rules-v1",\
                        "ts":"2026-03-01T10:15:30Z"}}""".formatted(transfer, transfer, sender)));
        assertThat(event.get("data").get("score").isNumber()).isTrue();
        assertThat(event.get("data").get("reasons").isArray()).isTrue();
    }
}
