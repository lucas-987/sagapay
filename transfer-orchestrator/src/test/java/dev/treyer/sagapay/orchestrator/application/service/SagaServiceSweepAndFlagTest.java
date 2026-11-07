package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.application.port.out.OutboxPort;
import dev.treyer.sagapay.orchestrator.application.port.out.SagaMetricsPort;
import dev.treyer.sagapay.orchestrator.application.port.out.SagaStepPort;
import dev.treyer.sagapay.orchestrator.application.port.out.TransferPort;
import dev.treyer.sagapay.orchestrator.application.service.SagaTransitionWriter.FlagOutcome;
import dev.treyer.sagapay.orchestrator.domain.FraudDecision;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferNotFoundException;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class SagaServiceSweepAndFlagTest {

    private static final int REPRISE_BATCH_SIZE = 7;

    private final TransferPort transfers = mock(TransferPort.class);
    private final LedgerPort ledger = mock(LedgerPort.class);
    private final SagaTransitionWriter writer = mock(SagaTransitionWriter.class);
    private final SagaMetricsPort metrics = mock(SagaMetricsPort.class);
    private final SagaService service = new SagaService(
            transfers,
            mock(SagaStepPort.class),
            mock(OutboxPort.class),
            ledger,
            new JsonMapper(),
            writer,
            metrics,
            Clock.systemUTC(),
            new SagaSettings(5000, 10000, 120000, 5000, 100, REPRISE_BATCH_SIZE),
            "code");

    private Transfer cleared(UUID id) {
        Transfer transfer = mock(Transfer.class);
        when(transfer.getId()).thenReturn(id);
        when(transfer.getStatus()).thenReturn(TransferStatus.CLEARED);
        when(transfer.getSenderAccountId()).thenReturn(UUID.randomUUID());
        when(transfer.getRecipientAccountId()).thenReturn(UUID.randomUUID());
        when(transfer.getAmount()).thenReturn(BigDecimal.TEN);
        when(transfer.getCurrency()).thenReturn("EUR");
        when(transfers.findById(id)).thenReturn(Optional.of(transfer));
        return transfer;
    }

    private void stuckCleared(Transfer... stuck) {
        when(transfers.findByStatusAndUpdatedAtBefore(eq(TransferStatus.CLEARED), any(), anyInt()))
                .thenReturn(List.of(stuck));
    }

    @Test
    void oneStuckTransferFailingDoesNotStopTheNextOnes() {
        UUID broken = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        Transfer brokenTransfer = cleared(broken);
        Transfer healthyTransfer = cleared(healthy);
        stuckCleared(brokenTransfer, healthyTransfer);
        when(transfers.findById(broken)).thenThrow(new IllegalStateException("corrupt row"));

        assertThat(service.sweepStuckTransfers()).isEqualTo(1);

        verify(writer).tryApplyPosted(eq(healthy), any());
    }

    @Test
    void anUnavailableLedgerStopsTheSweepAndKeepsTheCount() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Transfer firstTransfer = cleared(first);
        Transfer secondTransfer = cleared(second);
        stuckCleared(firstTransfer, secondTransfer);
        doThrow(new LedgerUnavailableException(new RuntimeException("down")))
                .when(ledger)
                .postTransfer(eq(second.toString()), any(), any(), any(Money.class));

        assertThat(service.sweepStuckTransfers()).isEqualTo(1);

        verify(transfers, never()).findFailedWithPendingRelease(any(), anyInt());
    }

    @Test
    void everyKindOfStuckWorkIsReadWithTheConfiguredBatchSize() {
        service.sweepStuckTransfers();

        verify(transfers).findByStatusAndUpdatedAtBefore(eq(TransferStatus.INITIATED), any(), eq(REPRISE_BATCH_SIZE));
        verify(transfers).findByStatusAndUpdatedAtBefore(eq(TransferStatus.CLEARED), any(), eq(REPRISE_BATCH_SIZE));
        verify(transfers).findFailedWithPendingRelease(any(), eq(REPRISE_BATCH_SIZE));
    }

    private FraudResult flagged(UUID transferId) {
        return new FraudResult(
                UUID.randomUUID(),
                transferId,
                FraudDecision.FLAGGED,
                new BigDecimal("0.9000"),
                List.of("NEW_RECIPIENT"));
    }

    @Test
    void aShortenedWindowIsCounted() {
        FraudResult result = flagged(UUID.randomUUID());
        when(writer.tryApplyFlagged(result)).thenReturn(FlagOutcome.BLOCKED_SHORTENED);

        service.applyFraudResult(result);

        verify(metrics).confirmationWindowShortened();
    }

    @Test
    void aWholeWindowIsNotCounted() {
        FraudResult result = flagged(UUID.randomUUID());
        when(writer.tryApplyFlagged(result)).thenReturn(FlagOutcome.BLOCKED);

        service.applyFraudResult(result);

        verify(metrics, never()).confirmationWindowShortened();
    }

    @Test
    void aFlaggedTransferWithNoTimeLeftIsReleasedAtOnce() {
        UUID id = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Transfer transfer = mock(Transfer.class);
        when(transfer.getId()).thenReturn(id);
        when(transfer.getReservationId()).thenReturn(reservationId);
        when(transfers.findById(id)).thenReturn(Optional.of(transfer));
        FraudResult result = flagged(id);
        when(writer.tryApplyFlagged(result)).thenReturn(FlagOutcome.EXPIRED);

        service.applyFraudResult(result);

        verify(ledger).releaseReservation(id.toString(), reservationId);
        verify(writer).recordReleased(id);
    }

    @Test
    void anUnavailableLedgerLeavesTheReleaseToTheReprisePoller() {
        UUID id = UUID.randomUUID();
        Transfer transfer = mock(Transfer.class);
        when(transfer.getId()).thenReturn(id);
        when(transfers.findById(id)).thenReturn(Optional.of(transfer));
        FraudResult result = flagged(id);
        when(writer.tryApplyFlagged(result)).thenReturn(FlagOutcome.EXPIRED);
        doThrow(new LedgerUnavailableException(new RuntimeException("down")))
                .when(ledger)
                .releaseReservation(any(), any());

        assertThatCode(() -> service.applyFraudResult(result)).doesNotThrowAnyException();

        verify(writer, never()).recordReleased(any());
    }

    @Test
    void advancingAnUnknownTransferReadsAsNotFound() {
        UUID id = UUID.randomUUID();
        when(transfers.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.advance(id)).isInstanceOf(TransferNotFoundException.class);
    }

    @Test
    void aRefusedReleaseIsAnErrorNamingTheTransferAndTheLedgerStatus(CapturedOutput output) {
        UUID id = UUID.randomUUID();
        Transfer transfer = mock(Transfer.class);
        when(transfer.getId()).thenReturn(id);
        when(transfers.findById(id)).thenReturn(Optional.of(transfer));
        FraudResult result = flagged(id);
        when(writer.tryApplyFlagged(result)).thenReturn(FlagOutcome.EXPIRED);
        doThrow(new LedgerRejectedException("NOT_FOUND", "no reservation", null))
                .when(ledger)
                .releaseReservation(any(), any());

        service.applyFraudResult(result);

        verify(writer).recordReleaseRefused(eq(id), any());
        assertThat(output.getOut())
                .contains("\"level\":\"ERROR\"")
                .contains("reservation release refused by the ledger")
                .contains("\"transfer.id\":\"" + id + "\"")
                .contains("\"ledger.status\":\"NOT_FOUND\"");
    }

    @Test
    void aRefusedPostIsAWarningNamingTheLedgerStatus(CapturedOutput output) {
        UUID id = UUID.randomUUID();
        cleared(id);
        doThrow(new LedgerRejectedException("NOT_FOUND", "expired", null))
                .when(ledger)
                .postTransfer(any(), any(), any(), any(Money.class));
        when(writer.tryApplyRejectedAndRelease(eq(id), any(), any(), any(), any(), any()))
                .thenReturn(true);

        service.postCleared(id);

        assertThat(output.getOut())
                .contains("\"level\":\"WARN\"")
                .contains("transfer failed, post refused by the ledger")
                .contains("\"ledger.status\":\"NOT_FOUND\"");
    }

    @Test
    void aClearedResultThatChangesNothingIsLoggedAsSkipped(CapturedOutput output) {
        UUID id = UUID.randomUUID();
        FraudResult result =
                new FraudResult(UUID.randomUUID(), id, FraudDecision.CLEARED, new BigDecimal("0.1000"), List.of());
        when(writer.tryApplyCleared(result)).thenReturn(false);

        service.applyFraudResult(result);

        assertThat(output.getOut()).contains("cleared fraud result skipped").contains(id.toString());
    }

    @Test
    void aPostedTransferIsLogged(CapturedOutput output) {
        UUID id = UUID.randomUUID();
        cleared(id);
        when(writer.tryApplyPosted(eq(id), any())).thenReturn(true);

        service.postCleared(id);

        assertThat(output.getOut()).contains("\"message\":\"transfer posted\"").contains(id.toString());
    }
}
