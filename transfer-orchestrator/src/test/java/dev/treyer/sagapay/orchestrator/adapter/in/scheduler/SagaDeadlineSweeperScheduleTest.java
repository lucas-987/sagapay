package dev.treyer.sagapay.orchestrator.adapter.in.scheduler;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** No explicit call: the scheduler itself, at the configured interval, ends the transfer. */
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "saga.deadline.sweep-interval-ms=100")
@SpringBootTest
class SagaDeadlineSweeperScheduleTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private LedgerPort ledger;

    @Test
    void theSchedulerEndsAnOverdueTransferAtTheConfiguredInterval() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency, status, reservation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'SCREENING'::transfer_status, ?)
                """,
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("25.0000"),
                "EUR",
                UUID.randomUUID());
        jdbcTemplate.update(
                "insert into saga_steps (transfer_id, step, outcome, deadline)"
                        + " values (?, 'SCREEN', 'RETRY', now() - interval '1 second')",
                id);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "select failure_reason from transfers where id = ?", String.class, id))
                        .isEqualTo("SCREENING_TIMEOUT"));
    }
}
