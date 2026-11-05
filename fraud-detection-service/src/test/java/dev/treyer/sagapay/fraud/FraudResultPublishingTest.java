package dev.treyer.sagapay.fraud;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FraudResultPublishingTest {

    private static final Duration PATIENCE = Duration.ofSeconds(60);

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();

    private void sendReserved(String eventId, UUID transfer, String amount) {
        String event = """
                {"id":"%s","source":"transfer-orchestrator","type":"FundsReserved.v1","time":"2026-03-01T10:15:30Z",\
                "subject":"%s","traceparent":null,"data":{"eventType":"FundsReserved","transferId":"%s",\
                "senderId":"%s","recipientId":"%s","amount":"%s","currency":"EUR"}}""".formatted(eventId, transfer, transfer, sender, recipient, amount);
        kafka.send("payments.transfers", sender.toString(), event).join();
    }

    private void awaitPublished(UUID transfer) {
        await().atMost(PATIENCE)
                .untilAsserted(() -> assertThat(jdbc.queryForList(
                                "select published_at is not null from outbox where aggregate_id = ?",
                                Boolean.class,
                                transfer))
                        .containsExactly(true));
    }

    private int count(String table, String column, UUID value) {
        return jdbc.queryForObject(
                "select count(*) from " + table + " where " + column + " = cast(? as uuid)",
                Integer.class,
                value.toString());
    }

    @Test
    @Tag("M3-S12")
    @DisplayName("A reserved transfer gets exactly one fraud result")
    void reservedTransferGetsExactlyOneFraudResult() {
        UUID transfer = UUID.randomUUID();

        sendReserved(UUID.randomUUID().toString(), transfer, "500.00");
        awaitPublished(transfer);

        List<ConsumerRecord<String, String>> records =
                FraudResultsTopic.recordsKeyedBy(consumerFactory, transfer.toString());
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().key()).isEqualTo(transfer.toString());
        JsonNode event = jsonMapper.readTree(records.getFirst().value());
        assertThat(event.get("type").asString()).isEqualTo("TransferFlagged.v1");
        ObjectNode result = (ObjectNode) event.get("data");
        assertThat(Instant.parse(result.remove("ts").asString())).isNotNull();
        assertThat(result).isEqualTo(jsonMapper.readTree("""
                        {"transferId":"%s","senderId":"%s","decision":"FLAGGED","score":0.7000,\
                        "reasons":["NEW_RECIPIENT","AMOUNT_ROUND"],"modelVersion":"rules-v1"}""".formatted(transfer, sender)));
    }

    @Test
    @Tag("M3-S14")
    @DisplayName("A reserved transfer delivered twice is screened once")
    void reservedTransferDeliveredTwiceIsScreenedOnce() {
        UUID transfer = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        sendReserved(eventId, transfer, "500.00");
        sendReserved(eventId, transfer, "500.00");
        sendReserved(UUID.randomUUID().toString(), next, "20.00");
        awaitPublished(next);
        awaitPublished(transfer);

        assertAll(
                () -> assertThat(FraudResultsTopic.recordsKeyedBy(consumerFactory, transfer.toString()))
                        .hasSize(1),
                () -> assertThat(count("fraud_cases", "transfer_id", transfer)).isEqualTo(1),
                () -> assertThat(redis.opsForValue().get("vel:" + sender + ":1h"))
                        .isEqualTo("2"));
    }
}
