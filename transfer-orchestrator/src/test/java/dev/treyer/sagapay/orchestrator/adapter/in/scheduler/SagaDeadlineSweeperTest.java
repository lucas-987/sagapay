package dev.treyer.sagapay.orchestrator.adapter.in.scheduler;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Transfers are inserted directly in their waiting state, so only the sweeper can
 * move them on. */
@Import(TestcontainersConfiguration.class)
// Only the explicit sweep() calls may run the sweeper.
@TestPropertySource(properties = "saga.deadline.sweep-interval-ms=3600000")
@SpringBootTest
class SagaDeadlineSweeperTest {

    @Autowired
    private SagaDeadlineSweeper sweeper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private LedgerPort ledger;

    @BeforeEach
    void cleanSlate() {
        jdbcTemplate.update("delete from saga_steps");
        jdbcTemplate.update("delete from transfers");
        Mockito.reset(ledger);
    }

    private UUID waitingTransfer(String status, long... screenDeadlineOffsetsSeconds) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency, status, reservation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::transfer_status, ?)
                """,
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("25.0000"),
                "EUR",
                status,
                UUID.randomUUID());
        for (long offset : screenDeadlineOffsetsSeconds) {
            jdbcTemplate.update(
                    "insert into saga_steps (transfer_id, step, outcome, deadline)"
                            + " values (?, 'SCREEN', 'RETRY', now() + make_interval(secs => ?))",
                    id,
                    offset);
        }
        return id;
    }

    private String statusOf(UUID transferId) {
        return jdbcTemplate.queryForObject("select status::text from transfers where id = ?", String.class, transferId);
    }

    private String failureReasonOf(UUID transferId) {
        return jdbcTemplate.queryForObject(
                "select failure_reason from transfers where id = ?", String.class, transferId);
    }

    private UUID reservationOf(UUID transferId) {
        return jdbcTemplate.queryForObject("select reservation_id from transfers where id = ?", UUID.class, transferId);
    }

    private List<String> releaseOutcomes(UUID transferId) {
        return jdbcTemplate.queryForList(
                "select outcome from saga_steps where transfer_id = ? and step = 'RELEASE' order by id",
                String.class,
                transferId);
    }

    @Test
    void sweepFailsOverdueTransfersOfBothWaitingStatusesAndReleasesEachReservationOnce() {
        UUID screening = waitingTransfer("SCREENING", -1);
        UUID blocked = waitingTransfer("BLOCKED", -60, -1);

        sweeper.sweep();
        sweeper.sweep();

        assertThat(statusOf(screening)).isEqualTo("FAILED");
        assertThat(failureReasonOf(screening)).isEqualTo("SCREENING_TIMEOUT");
        assertThat(statusOf(blocked)).isEqualTo("FAILED");
        assertThat(failureReasonOf(blocked)).isEqualTo("VERIFICATION_EXPIRED");
        assertThat(releaseOutcomes(screening)).containsExactly("RETRY", "COMPENSATED");
        assertThat(releaseOutcomes(blocked)).containsExactly("RETRY", "COMPENSATED");
        verify(ledger, times(1)).releaseReservation(screening.toString(), reservationOf(screening));
        verify(ledger, times(1)).releaseReservation(blocked.toString(), reservationOf(blocked));
    }

    @Test
    void sweepLeavesTransfersBeforeTheirDeadlineAlone() {
        UUID screening = waitingTransfer("SCREENING", 3600);
        UUID blocked = waitingTransfer("BLOCKED", -60, 3600);

        sweeper.sweep();

        assertThat(statusOf(screening)).isEqualTo("SCREENING");
        assertThat(statusOf(blocked)).isEqualTo("BLOCKED");
        verify(ledger, never()).releaseReservation(any(), any());
    }

    @Test
    void sweepStopsAtTheFirstUnavailableLedgerCallAndTheNextSweepEndsTheOthers() {
        UUID first = waitingTransfer("SCREENING", -2);
        UUID second = waitingTransfer("SCREENING", -1);
        doThrow(new LedgerUnavailableException(new RuntimeException("deadline exceeded")))
                .when(ledger)
                .releaseReservation(any(), any());

        sweeper.sweep();

        verify(ledger, times(1)).releaseReservation(any(), any());
        assertThat(statusOf(first)).isEqualTo("FAILED");
        assertThat(statusOf(second)).isEqualTo("SCREENING");

        Mockito.reset(ledger); // ledger back
        sweeper.sweep();

        assertThat(statusOf(first)).isEqualTo("FAILED");
        assertThat(statusOf(second)).isEqualTo("FAILED");
    }
}
