package dev.treyer.sagapay.orchestrator;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MigrationUpgradeTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .placeholders(Map.of(
                        "orchestratorAppUsername", "orchestrator_app",
                        "orchestratorAppPassword", "secret"))
                .target(target)
                .load();
    }

    @Test
    void upgradingADatabaseHoldingEarlierRowsKeepsThemAndAddsTheNewObjects() {
        flyway("4").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID transferId = UUID.randomUUID();
        jdbc.update(
                """
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency, status)
                values (?, ?, ?, ?, ?, ?, 10, 'EUR', 'POSTED')
                """,
                transferId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID());
        jdbc.update("insert into saga_steps (transfer_id, step, outcome) values (?, 'RESERVE', 'OK')", transferId);

        flyway("latest").migrate();

        assertThat(jdbc.queryForObject("select status::text from transfers where id = ?", String.class, transferId))
                .isEqualTo("POSTED");
        assertThat(jdbc.queryForObject(
                        "select fraud_score is null and fraud_reasons is null from transfers where id = ?",
                        Boolean.class,
                        transferId))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select deadline is null from saga_steps where transfer_id = ?", Boolean.class, transferId))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select data_type from information_schema.columns"
                                + " where table_name = 'transfers' and column_name = 'status'",
                        String.class))
                .isEqualTo("text");
        assertThat(jdbc.queryForObject("select count(*) from pg_type where typname = 'transfer_status'", Integer.class))
                .isZero();
    }

    private static void insertOutboxRow(JdbcTemplate jdbc, UUID id, String payload) {
        jdbc.update(
                "insert into outbox (id, aggregate_type, aggregate_id, event_type, payload)"
                        + " values (?, 'Transfer', ?, 'TransferInitiated', cast(? as jsonb))",
                id,
                UUID.randomUUID(),
                payload);
    }

    private static UUID insertFailedTransfer(JdbcTemplate jdbc) {
        UUID transferId = UUID.randomUUID();
        jdbc.update(
                """
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency, status)
                values (?, ?, ?, ?, ?, ?, 10, 'EUR', 'FAILED')
                """,
                transferId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID());
        return transferId;
    }
}
