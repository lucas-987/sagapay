package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.application.port.out.OutboxPort;
import dev.treyer.sagapay.orchestrator.application.port.out.ProcessedEventPort;
import dev.treyer.sagapay.orchestrator.application.port.out.SagaStepPort;
import dev.treyer.sagapay.orchestrator.application.port.out.TransferPort;
import dev.treyer.sagapay.orchestrator.application.service.SagaTransitionWriter.FlagOutcome;
import dev.treyer.sagapay.orchestrator.domain.FraudDecision;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.SagaStepName;
import dev.treyer.sagapay.orchestrator.domain.SagaStepOutcome;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SagaTransitionWriterFlagTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final long WINDOW_MS = 120_000;
    private static final long MARGIN_MS = 5_000;
    private static final BigDecimal SCORE = new BigDecimal("0.9000");
    private static final List<String> REASONS = List.of("NEW_RECIPIENT");

    private final TransferPort transfers = mock(TransferPort.class);
    private final SagaStepPort sagaSteps = mock(SagaStepPort.class);
    private final OutboxPort outbox = mock(OutboxPort.class);
    private final ProcessedEventPort processedEvents = mock(ProcessedEventPort.class);
    private final SagaTransitionWriter writer = new SagaTransitionWriter(
            transfers,
            sagaSteps,
            outbox,
            processedEvents,
            new JsonMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new SagaSettings(5000, 10000, WINDOW_MS, MARGIN_MS, 100, 100));

    private final UUID transferId = UUID.randomUUID();
    private final FraudResult flagged =
            new FraudResult(UUID.randomUUID(), transferId, FraudDecision.FLAGGED, SCORE, REASONS);

    @BeforeEach
    void setUp() {
        when(processedEvents.tryMarkProcessed(flagged.eventId(), "orchestrator-fraud"))
                .thenReturn(true);
        when(transfers.transitionToBlocked(transferId, SCORE, REASONS, NOW)).thenReturn(1);
        when(transfers.transitionFlaggedToExpired(transferId, SCORE, REASONS, NOW))
                .thenReturn(1);
    }

    private void screening(Instant reservationExpiresAt) {
        Transfer transfer = mock(Transfer.class);
        when(transfer.getId()).thenReturn(transferId);
        when(transfer.getSenderId()).thenReturn(UUID.randomUUID());
        when(transfer.getRecipientId()).thenReturn(UUID.randomUUID());
        when(transfer.getAmount()).thenReturn(BigDecimal.TEN);
        when(transfer.getCurrency()).thenReturn("EUR");
        when(transfer.getStatus()).thenReturn(TransferStatus.SCREENING);
        when(transfer.getReservationExpiresAt()).thenReturn(reservationExpiresAt);
        when(transfers.findById(transferId)).thenReturn(Optional.of(transfer));
    }

    private List<SagaStep> savedSteps(int count) {
        ArgumentCaptor<SagaStep> saved = ArgumentCaptor.forClass(SagaStep.class);
        verify(sagaSteps, times(count)).save(saved.capture());
        return saved.getAllValues();
    }

    @Test
    void aReservationOutlastingTheWindowLeavesItWhole() {
        screening(NOW.plusMillis(WINDOW_MS + MARGIN_MS + 1));

        assertThat(writer.tryApplyFlagged(flagged)).isEqualTo(FlagOutcome.BLOCKED);

        assertThat(savedSteps(1).get(0).getDeadline()).isEqualTo(NOW.plusMillis(WINDOW_MS));
    }

    @Test
    void aReservationEndingExactlyAfterTheMarginLeavesTheWindowWhole() {
        screening(NOW.plusMillis(WINDOW_MS + MARGIN_MS));

        assertThat(writer.tryApplyFlagged(flagged)).isEqualTo(FlagOutcome.BLOCKED);

        assertThat(savedSteps(1).get(0).getDeadline()).isEqualTo(NOW.plusMillis(WINDOW_MS));
    }

    @Test
    @Tag("M3-S50")
    @DisplayName("A reservation expiring first shortens the confirmation window")
    void aReservationExpiringFirstShortensTheWindow() {
        Instant expiresAt = NOW.plusSeconds(60);
        screening(expiresAt);

        assertThat(writer.tryApplyFlagged(flagged)).isEqualTo(FlagOutcome.BLOCKED_SHORTENED);

        assertThat(savedSteps(1).get(0).getDeadline()).isEqualTo(expiresAt.minusMillis(MARGIN_MS));
    }

    @Test
    @Tag("M3-S51")
    @DisplayName("A reservation leaving no time to confirm fails the transfer and releases it")
    void aReservationLeavingNoTimeFailsTheTransfer() {
        screening(NOW.plusMillis(MARGIN_MS));

        assertThat(writer.tryApplyFlagged(flagged)).isEqualTo(FlagOutcome.EXPIRED);

        verify(transfers, never()).transitionToBlocked(any(), any(), any(), any());
        verify(transfers).setReleasePending(transferId, true);
        verify(outbox).save(any(OutboxRow.class));
        assertThat(savedSteps(2))
                .extracting(SagaStep::getStep, SagaStep::getOutcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(SagaStepName.SCREEN, SagaStepOutcome.FAILED),
                        org.assertj.core.groups.Tuple.tuple(SagaStepName.RELEASE, SagaStepOutcome.RETRY));
    }

    @Test
    void aFlagForATransferNoLongerInScreeningIsRecordedAndChangesNothing() {
        Transfer failed = mock(Transfer.class);
        when(failed.getStatus()).thenReturn(TransferStatus.FAILED);
        when(transfers.findById(transferId)).thenReturn(Optional.of(failed));

        assertThat(writer.tryApplyFlagged(flagged)).isEqualTo(FlagOutcome.SKIPPED);

        verify(transfers, never()).transitionToBlocked(any(), any(), any(), any());
        verify(sagaSteps, never()).save(any());
    }

    @Test
    void aFlagForAnUnknownTransferIsRecordedAndChangesNothing() {
        when(transfers.findById(transferId)).thenReturn(Optional.empty());

        assertThat(writer.tryApplyFlagged(flagged)).isEqualTo(FlagOutcome.SKIPPED);

        verify(sagaSteps, never()).save(any());
    }

    @Test
    void aFlagArrivingPastTheScreeningDeadlineChangesNothing() {
        screening(NOW.plusSeconds(300));
        when(transfers.transitionToBlocked(transferId, SCORE, REASONS, NOW)).thenReturn(0);

        assertThat(writer.tryApplyFlagged(flagged)).isEqualTo(FlagOutcome.SKIPPED);

        verify(sagaSteps, never()).save(any());
    }

    @Test
    void aFlagWithNoTimeLeftArrivingPastTheScreeningDeadlineChangesNothing() {
        screening(NOW);
        when(transfers.transitionFlaggedToExpired(transferId, SCORE, REASONS, NOW))
                .thenReturn(0);

        assertThat(writer.tryApplyFlagged(flagged)).isEqualTo(FlagOutcome.SKIPPED);

        verify(sagaSteps, never()).save(any());
        verify(outbox, never()).save(any());
    }
}
