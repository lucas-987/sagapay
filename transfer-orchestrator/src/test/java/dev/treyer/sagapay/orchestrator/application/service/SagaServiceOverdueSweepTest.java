package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.application.port.out.OutboxPort;
import dev.treyer.sagapay.orchestrator.application.port.out.SagaStepPort;
import dev.treyer.sagapay.orchestrator.application.port.out.TransferPort;
import dev.treyer.sagapay.orchestrator.domain.FailureReason;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SagaServiceOverdueSweepTest {

    private final TransferPort transfers = mock(TransferPort.class);
    private final LedgerPort ledger = mock(LedgerPort.class);
    private final SagaTransitionWriter writer = mock(SagaTransitionWriter.class);
    private final SagaService service = new SagaService(
            transfers,
            mock(SagaStepPort.class),
            mock(OutboxPort.class),
            ledger,
            new JsonMapper(),
            writer,
            5000,
            "code");

    private Transfer transfer() {
        return new Transfer(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("25.0000"),
                "EUR",
                null);
    }

    @Test
    void aTransferAnotherActorMovedOnSinceTheQueryIsNeitherCountedNorReleased() {
        Transfer overdue = new Transfer(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("25.0000"),
                "EUR",
                null);
        when(transfers.findOverdue(eq(TransferStatus.SCREENING), any(), anyInt()))
                .thenReturn(List.of(overdue));
        when(writer.applyTimedOut(overdue.getId(), overdue, TransferStatus.SCREENING, FailureReason.SCREENING_TIMEOUT))
                .thenReturn(false);

        int ended = service.sweepOverdueTransfers();

        assertThat(ended).isZero();
        verify(ledger, never()).releaseReservation(any(), any());
    }

    @Test
    void aTransferThatCannotBeFailedDoesNotStopTheSweepOfTheOthers() {
        Transfer broken = transfer();
        Transfer healthy = transfer();
        when(transfers.findOverdue(eq(TransferStatus.SCREENING), any(), anyInt()))
                .thenReturn(List.of(broken, healthy));
        when(writer.applyTimedOut(broken.getId(), broken, TransferStatus.SCREENING, FailureReason.SCREENING_TIMEOUT))
                .thenThrow(new IllegalStateException("outbox payload cannot be written"));
        when(writer.applyTimedOut(healthy.getId(), healthy, TransferStatus.SCREENING, FailureReason.SCREENING_TIMEOUT))
                .thenReturn(true);

        int ended = service.sweepOverdueTransfers();

        assertThat(ended).isEqualTo(1);
        verify(ledger).releaseReservation(healthy.getId().toString(), healthy.getReservationId());
        verify(ledger, never()).releaseReservation(broken.getId().toString(), broken.getReservationId());
    }

    @Test
    void eachWaitingStatusIsQueriedWithTheBatchLimitOf100() {
        service.sweepOverdueTransfers();

        verify(transfers).findOverdue(eq(TransferStatus.SCREENING), any(Instant.class), eq(100));
        verify(transfers).findOverdue(eq(TransferStatus.BLOCKED), any(Instant.class), eq(100));
        verifyNoMoreInteractions(transfers);
    }

    @Test
    void anUnavailableLedgerLeavesTheBlockedTransferForTheNextSweep() {
        Transfer screening = transfer();
        Transfer blocked = transfer();
        when(transfers.findOverdue(eq(TransferStatus.SCREENING), any(), anyInt()))
                .thenReturn(List.of(screening))
                .thenReturn(List.of());
        when(transfers.findOverdue(eq(TransferStatus.BLOCKED), any(), anyInt())).thenReturn(List.of(blocked));
        when(writer.applyTimedOut(
                        screening.getId(), screening, TransferStatus.SCREENING, FailureReason.SCREENING_TIMEOUT))
                .thenReturn(true);
        when(writer.applyTimedOut(blocked.getId(), blocked, TransferStatus.BLOCKED, FailureReason.VERIFICATION_EXPIRED))
                .thenReturn(true);
        doThrow(new LedgerUnavailableException(new RuntimeException("down")))
                .doNothing()
                .when(ledger)
                .releaseReservation(screening.getId().toString(), screening.getReservationId());

        int firstSweep = service.sweepOverdueTransfers();

        assertThat(firstSweep).isEqualTo(1);
        verify(writer, never()).applyTimedOut(any(), eq(blocked), any(), any());

        int secondSweep = service.sweepOverdueTransfers();

        assertThat(secondSweep).isEqualTo(1);
        verify(writer)
                .applyTimedOut(blocked.getId(), blocked, TransferStatus.BLOCKED, FailureReason.VERIFICATION_EXPIRED);
        verify(ledger).releaseReservation(blocked.getId().toString(), blocked.getReservationId());
    }

    @Test
    void aReleaseThatFailsUnexpectedlyDoesNotStopTheSweepOfTheOthers() {
        Transfer broken = transfer();
        Transfer healthy = transfer();
        when(transfers.findOverdue(eq(TransferStatus.SCREENING), any(), anyInt()))
                .thenReturn(List.of(broken, healthy));
        when(writer.applyTimedOut(broken.getId(), broken, TransferStatus.SCREENING, FailureReason.SCREENING_TIMEOUT))
                .thenReturn(true);
        when(writer.applyTimedOut(healthy.getId(), healthy, TransferStatus.SCREENING, FailureReason.SCREENING_TIMEOUT))
                .thenReturn(true);
        doThrow(new IllegalStateException("unexpected"))
                .when(ledger)
                .releaseReservation(broken.getId().toString(), broken.getReservationId());

        int ended = service.sweepOverdueTransfers();

        assertThat(ended).isEqualTo(2);
        verify(ledger).releaseReservation(healthy.getId().toString(), healthy.getReservationId());
        verify(writer).recordReleased(healthy.getId());
        verify(writer, never()).recordReleased(broken.getId());
    }
}
