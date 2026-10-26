package dev.treyer.sagapay.fraud.adapter.out.persistence;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.fraud.TestcontainersConfiguration;
import dev.treyer.sagapay.fraud.domain.FraudCase;
import dev.treyer.sagapay.fraud.domain.FraudRule;
import dev.treyer.sagapay.fraud.domain.OutboxMessage;
import dev.treyer.sagapay.fraud.domain.SenderActivity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class FraudPersistenceAdaptersTest {

    private static final UUID TRANSFER = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID SENDER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECIPIENT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JdbcClient jdbc() {
        return JdbcClient.create(jdbcTemplate);
    }

    @Test
    void anEventSeenForTheFirstTimeIsRecordedAsProcessed() {
        boolean first = new JdbcProcessedEventAdapter(jdbc()).markProcessed("event-1", "fraud-screening");

        assertThat(first).isTrue();
        assertThat(jdbcTemplate.queryForList("select event_id, consumer from processed_events"))
                .containsExactly(Map.of("event_id", "event-1", "consumer", "fraud-screening"));
    }

    @Test
    void anEventSeenAgainIsReportedAsDuplicateAndRecordedOnce() {
        JdbcProcessedEventAdapter adapter = new JdbcProcessedEventAdapter(jdbc());
        adapter.markProcessed("event-1", "fraud-screening");

        boolean second = adapter.markProcessed("event-1", "fraud-screening");

        assertThat(second).isFalse();
        assertThat(jdbcTemplate.queryForObject("select count(*) from processed_events", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aFraudCaseIsStoredOpenWithItsScoreReasonsAndSnapshot() {
        new JdbcFraudCaseAdapter(jdbc(), jsonMapper)
                .open(new FraudCase(
                        TRANSFER,
                        SENDER,
                        Money.of("500.00", "EUR"),
                        new BigDecimal("0.70"),
                        List.of(FraudRule.NEW_RECIPIENT, FraudRule.AMOUNT_ROUND),
                        new SenderActivity(3, Set.of(RECIPIENT))));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select transfer_id, sender_id, amount, currency, score, array_to_string(reasons, ',') as reasons,"
                        + " status, transfer_outcome, velocity_snapshot::text as snapshot, decided_at"
                        + " from fraud_cases");
        assertThat(row.get("transfer_id")).isEqualTo(TRANSFER.toString());
        assertThat(row.get("sender_id")).isEqualTo(SENDER.toString());
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("500");
        assertThat(row.get("currency")).isEqualTo("EUR");
        assertThat((BigDecimal) row.get("score")).isEqualByComparingTo("0.70");
        assertThat(row.get("reasons")).isEqualTo("NEW_RECIPIENT,AMOUNT_ROUND");
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("transfer_outcome")).isNull();
        assertThat(row.get("decided_at")).isNull();
        assertThat(jsonMapper.readTree((String) row.get("snapshot")))
                .isEqualTo(jsonMapper.readTree("{\"transfersInLastHour\":3,\"recentRecipientCount\":1}"));
    }

    @Test
    void anOutboxMessageIsStoredUnpublishedWithItsPayload() {
        UUID id = UUID.randomUUID();

        new JdbcOutboxAdapter(jdbc())
                .save(new OutboxMessage(
                        id, "Transfer", TRANSFER.toString(), "TransferCleared", "{\"decision\":\"CLEARED\"}"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select id, aggregate_type, aggregate_id, event_type, payload::text as payload,"
                        + " headers::text as headers, published_at from outbox");
        assertThat(row.get("id")).isEqualTo(id);
        assertThat(row.get("aggregate_type")).isEqualTo("Transfer");
        assertThat(row.get("aggregate_id")).isEqualTo(TRANSFER.toString());
        assertThat(row.get("event_type")).isEqualTo("TransferCleared");
        assertThat(jsonMapper.readTree((String) row.get("payload")))
                .isEqualTo(jsonMapper.readTree("{\"decision\":\"CLEARED\"}"));
        assertThat(row.get("headers")).isEqualTo("{}");
        assertThat(row.get("published_at")).isNull();
    }
}
