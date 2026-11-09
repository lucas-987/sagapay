package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.TransferRepository;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.application.port.out.OutboxPort;
import dev.treyer.sagapay.orchestrator.application.service.SagaTransitionWriter.FlagOutcome;
import dev.treyer.sagapay.orchestrator.domain.FraudDecision;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@Import({TestcontainersConfiguration.class, TransferBlockedOutboxTest.ReservingLedgerConfig.class})
@SpringBootTest(properties = {"saga.deadline.sweep-interval-ms=3600000", "saga.reprise.sweep-interval-ms=3600000"})
class TransferBlockedOutboxTest {

    private static final String BLOCKED_EVENT = "TransferBlocked";

    @Autowired
    private SagaService sagaService;

    @Autowired
    private SagaTransitionWriter writer;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private ReservingLedgerPort ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private OutboxPort outbox;

    private final JsonMapper jsonMapper = new JsonMapper();
    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();

    private UUID initiated() {
        return sagaService
                .initiateTransfer(
                        sender,
                        UUID.randomUUID(),
                        recipient,
                        UUID.randomUUID(),
                        Money.of("80.00", "EUR"),
                        UUID.randomUUID(),
                        null)
                .transfer()
                .getId();
    }

    private UUID screening(Instant reservationExpiresAt) {
        ledger.willReserveUntil(reservationExpiresAt);
        UUID id = initiated();
        sagaService.advance(id);
        assertThat(statusOf(id)).isEqualTo(TransferStatus.SCREENING);
        return id;
    }

    private static FraudResult flagged(UUID transferId) {
        return new FraudResult(
                UUID.randomUUID(), transferId, FraudDecision.FLAGGED, new BigDecimal("0.9000"), List.of("VELOCITY"));
    }

    private TransferStatus statusOf(UUID id) {
        return transferRepository.findById(id).orElseThrow().getStatus();
    }

    private List<Map<String, Object>> outboxRows(UUID transferId, String eventType) {
        return jdbc.queryForList(
                "select partition_key, payload::text as payload from outbox where aggregate_id = ? and event_type = ?",
                transferId,
                eventType);
    }

    private void assertOneBlockedRowFor(UUID id) {
        List<Map<String, Object>> rows = outboxRows(id, BLOCKED_EVENT);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("partition_key")).isEqualTo(sender.toString());
        assertThat(jsonMapper.readTree((String) rows.getFirst().get("payload")))
                .isEqualTo(jsonMapper.readTree("""
                        {"transferId":"%s","senderId":"%s","recipientId":"%s","amount":"80.0000",\
                        "currency":"EUR","eventType":"TransferBlocked"}""".formatted(id, sender, recipient)));
    }

    @Test
    @DisplayName("A flagged transfer moved to BLOCKED writes one TransferBlocked row")
    void aBlockedTransferWritesOneRow() {
        UUID id = screening(Instant.now().plusSeconds(600));

        assertThat(writer.tryApplyFlagged(flagged(id))).isEqualTo(FlagOutcome.BLOCKED);

        assertThat(statusOf(id)).isEqualTo(TransferStatus.BLOCKED);
        assertOneBlockedRowFor(id);
    }

    @Test
    @DisplayName("A flagged transfer blocked with a shortened window writes one TransferBlocked row")
    void aShortenedBlockWritesOneRow() {
        UUID id = screening(Instant.now().plusSeconds(60));

        assertThat(writer.tryApplyFlagged(flagged(id))).isEqualTo(FlagOutcome.BLOCKED_SHORTENED);

        assertThat(statusOf(id)).isEqualTo(TransferStatus.BLOCKED);
        assertOneBlockedRowFor(id);
    }

    @Test
    @DisplayName("A flagged transfer with no time left fails and writes no TransferBlocked row")
    void anExpiredFlagWritesNoBlockedRow() {
        UUID id = screening(Instant.now().plusSeconds(2));

        assertThat(writer.tryApplyFlagged(flagged(id))).isEqualTo(FlagOutcome.EXPIRED);

        assertThat(statusOf(id)).isEqualTo(TransferStatus.FAILED);
        assertThat(outboxRows(id, "TransferFailed")).hasSize(1);
        assertThat(outboxRows(id, BLOCKED_EVENT)).isEmpty();
    }

    @Test
    @DisplayName("A repeated flag writes no second TransferBlocked row")
    void aRepeatedFlagWritesNoSecondRow() {
        UUID id = screening(Instant.now().plusSeconds(600));
        FraudResult result = flagged(id);
        writer.tryApplyFlagged(result);

        assertThat(writer.tryApplyFlagged(result)).isEqualTo(FlagOutcome.SKIPPED);

        assertOneBlockedRowFor(id);
    }

    @Test
    @DisplayName("A late flag for a transfer no longer in screening writes no TransferBlocked row")
    void aLateFlagWritesNoRow() {
        UUID id = initiated();

        assertThat(writer.tryApplyFlagged(flagged(id))).isEqualTo(FlagOutcome.SKIPPED);

        assertThat(statusOf(id)).isEqualTo(TransferStatus.INITIATED);
        assertThat(outboxRows(id, BLOCKED_EVENT)).isEmpty();
    }

    @Test
    @DisplayName("A rolled back block leaves neither the status change nor the row")
    void aRolledBackBlockLeavesNothing() {
        UUID id = screening(Instant.now().plusSeconds(600));
        FraudResult result = flagged(id);
        doThrow(new IllegalStateException("outbox down"))
                .when(outbox)
                .save(argThat((OutboxRow row) -> BLOCKED_EVENT.equals(row.getEventType())));

        try {
            assertThatThrownBy(() -> writer.tryApplyFlagged(result))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("outbox down");
        } finally {
            reset(outbox);
        }

        assertThat(statusOf(id)).isEqualTo(TransferStatus.SCREENING);
        assertThat(outboxRows(id, BLOCKED_EVENT)).isEmpty();
        assertThat(jdbc.queryForObject(
                        "select count(*) from processed_events where event_id = ?", Integer.class, result.eventId()))
                .isZero();
    }

    static class ReservingLedgerPort implements LedgerPort {

        private volatile Instant expiresAt = Instant.now().plusSeconds(600);

        void willReserveUntil(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }

        @Override
        public ReservationResult checkAndReserve(String transferId, UUID fromAccountId, Money amount) {
            return new ReservationResult.Ok(UUID.randomUUID(), expiresAt);
        }

        @Override
        public void postTransfer(String transferId, UUID fromAccountId, UUID toAccountId, Money amount) {}

        @Override
        public void releaseReservation(String transferId, UUID reservationId) {}
    }

    @TestConfiguration
    static class ReservingLedgerConfig {
        @Bean
        @Primary
        ReservingLedgerPort reservingLedgerPort() {
            return new ReservingLedgerPort();
        }
    }
}
