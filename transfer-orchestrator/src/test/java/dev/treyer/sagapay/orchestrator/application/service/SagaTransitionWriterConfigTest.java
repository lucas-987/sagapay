package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.application.port.out.OutboxPort;
import dev.treyer.sagapay.orchestrator.application.port.out.ProcessedEventPort;
import dev.treyer.sagapay.orchestrator.application.port.out.SagaStepPort;
import dev.treyer.sagapay.orchestrator.application.port.out.TransferPort;
import dev.treyer.sagapay.orchestrator.domain.FraudDecision;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reads the writer's configuration through Spring's property binding, in a context of its own. */
class SagaTransitionWriterConfigTest {

    @Test
    void theScreeningDeadlineFollowsTheConfiguredTimeout() {
        TransferPort transfers = mock(TransferPort.class);
        SagaStepPort sagaSteps = mock(SagaStepPort.class);
        UUID transferId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        when(transfers.transitionWithReservation(
                        transferId, TransferStatus.INITIATED, TransferStatus.SCREENING, reservationId))
                .thenReturn(1);
        Transfer transfer = new Transfer(
                transferId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("25.0000"),
                "EUR",
                null);

        new ApplicationContextRunner()
                .withBean(TransferPort.class, () -> transfers)
                .withBean(SagaStepPort.class, () -> sagaSteps)
                .withBean(OutboxPort.class, () -> mock(OutboxPort.class))
                .withBean(ProcessedEventPort.class, () -> mock(ProcessedEventPort.class))
                .withBean(JsonMapper.class, JsonMapper::new)
                .withBean(SagaTransitionWriter.class)
                .withPropertyValues("saga.screening.timeout-ms=2500")
                .run(context -> {
                    Instant before = Instant.now();
                    context.getBean(SagaTransitionWriter.class).applyReserved(transferId, transfer, reservationId);
                    Instant after = Instant.now();

                    ArgumentCaptor<SagaStep> saved = ArgumentCaptor.forClass(SagaStep.class);
                    verify(sagaSteps, times(2)).save(saved.capture());
                    SagaStep screen = saved.getAllValues().get(1);
                    assertThat(screen.getStep()).isEqualTo("SCREEN");
                    assertThat(screen.getDeadline()).isBetween(before.plusMillis(2500), after.plusMillis(2500));
                });
    }

    @Test
    void theConfirmationDeadlineFollowsTheConfiguredWindow() {
        TransferPort transfers = mock(TransferPort.class);
        SagaStepPort sagaSteps = mock(SagaStepPort.class);
        ProcessedEventPort processedEvents = mock(ProcessedEventPort.class);
        UUID transferId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        BigDecimal score = new BigDecimal("0.9000");
        List<String> reasons = List.of("VELOCITY_1H");
        when(processedEvents.markProcessed(eventId, "orchestrator-fraud")).thenReturn(true);
        when(transfers.transitionToBlocked(transferId, score, reasons)).thenReturn(1);

        new ApplicationContextRunner()
                .withBean(TransferPort.class, () -> transfers)
                .withBean(SagaStepPort.class, () -> sagaSteps)
                .withBean(OutboxPort.class, () -> mock(OutboxPort.class))
                .withBean(ProcessedEventPort.class, () -> processedEvents)
                .withBean(JsonMapper.class, JsonMapper::new)
                .withBean(SagaTransitionWriter.class)
                .withPropertyValues("saga.confirmation.window-ms=2500")
                .run(context -> {
                    Instant before = Instant.now();
                    context.getBean(SagaTransitionWriter.class)
                            .applyFlagged(new FraudResult(eventId, transferId, FraudDecision.FLAGGED, score, reasons));
                    Instant after = Instant.now();

                    ArgumentCaptor<SagaStep> saved = ArgumentCaptor.forClass(SagaStep.class);
                    verify(sagaSteps).save(saved.capture());
                    assertThat(saved.getValue().getStep()).isEqualTo("SCREEN");
                    assertThat(saved.getValue().getDeadline())
                            .isBetween(before.plusMillis(2500), after.plusMillis(2500));
                });
    }
}
