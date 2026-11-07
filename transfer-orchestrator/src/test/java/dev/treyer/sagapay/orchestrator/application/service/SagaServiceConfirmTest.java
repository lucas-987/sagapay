package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.application.port.out.OutboxPort;
import dev.treyer.sagapay.orchestrator.application.port.out.SagaStepPort;
import dev.treyer.sagapay.orchestrator.application.port.out.TransferPort;
import dev.treyer.sagapay.orchestrator.domain.FailureReason;
import dev.treyer.sagapay.orchestrator.domain.InvalidVerificationCodeException;
import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.SagaStepName;
import dev.treyer.sagapay.orchestrator.domain.SagaStepOutcome;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferNotBlockedException;
import dev.treyer.sagapay.orchestrator.domain.TransferNotFoundException;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import dev.treyer.sagapay.orchestrator.domain.VerificationExpiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The paths of the confirmation that need a transfer moving under the caller's feet, or no database. */
class SagaServiceConfirmTest {

    private static final String CODE = "right-code";
    private static final SagaSettings SETTINGS = new SagaSettings(5000, 10000, 120000, 100);

    private final TransferPort transfers = mock(TransferPort.class);
    private final SagaStepPort sagaSteps = mock(SagaStepPort.class);
    private final LedgerPort ledger = mock(LedgerPort.class);
    private final SagaTransitionWriter writer = mock(SagaTransitionWriter.class);
    private final SagaService service = new SagaService(
            transfers, sagaSteps, mock(OutboxPort.class), ledger, new JsonMapper(), writer, Clock.systemUTC(), SETTINGS, CODE);

    private static final UUID TRANSFER_ID = UUID.fromString("0a1b2c3d-0000-4000-8000-000000000001");
    private static final UUID SENDER_ID = UUID.fromString("0a1b2c3d-0000-4000-8000-000000000002");

    /** The status has no setter (transitions are conditional updates), so a test sets the state it needs. */
    private static Transfer transferIn(TransferStatus status, FailureReason failureReason) {
        Transfer transfer = new Transfer(
                TRANSFER_ID,
                UUID.randomUUID(),
                SENDER_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("25.0000"),
                "EUR",
                null,
                Instant.now());
        ReflectionTestUtils.setField(transfer, "status", status);
        ReflectionTestUtils.setField(transfer, "failureReason", failureReason);
        return transfer;
    }

    private static Transfer blockedTransfer() {
        return transferIn(TransferStatus.BLOCKED, null);
    }

    private void blockedTransferOpenFor(long seconds) {
        when(sagaSteps.findByTransferId(TRANSFER_ID))
                .thenReturn(List.of(new SagaStep(
                        TRANSFER_ID,
                        SagaStepName.SCREEN,
                        SagaStepOutcome.RETRY,
                        null,
                        Instant.now().plusSeconds(seconds),
                        Instant.now())));
    }

    @Test
    void aConfirmationLostToTheExpiryJobIsRefusedAsExpired() {
        when(transfers.findById(TRANSFER_ID))
                .thenReturn(Optional.of(blockedTransfer()))
                .thenReturn(Optional.of(transferIn(TransferStatus.FAILED, FailureReason.VERIFICATION_EXPIRED)));
        blockedTransferOpenFor(60);
        when(writer.tryApplyConfirmed(eq(TRANSFER_ID), any(Instant.class))).thenReturn(false);

        assertThatThrownBy(() -> service.confirmTransfer(TRANSFER_ID, SENDER_ID, CODE))
                .isInstanceOf(VerificationExpiredException.class)
                .hasMessage("the confirmation window of transfer " + TRANSFER_ID + " has closed");
        verify(ledger, never()).postTransfer(any(), any(), any(), any());
    }

    @Test
    void aConfirmationLostToAnotherConfirmationIsRefusedAsNotBlocked() {
        when(transfers.findById(TRANSFER_ID))
                .thenReturn(Optional.of(blockedTransfer()))
                .thenReturn(Optional.of(transferIn(TransferStatus.CLEARED, null)));
        blockedTransferOpenFor(60);
        when(writer.tryApplyConfirmed(eq(TRANSFER_ID), any(Instant.class))).thenReturn(false);

        assertThatThrownBy(() -> service.confirmTransfer(TRANSFER_ID, SENDER_ID, CODE))
                .isInstanceOf(TransferNotBlockedException.class)
                .hasMessage("transfer " + TRANSFER_ID + " is not BLOCKED");
    }

    @Test
    void aConfirmationLostToAnotherFailureIsRefusedAsNotBlocked() {
        when(transfers.findById(TRANSFER_ID))
                .thenReturn(Optional.of(blockedTransfer()))
                .thenReturn(Optional.of(transferIn(TransferStatus.FAILED, FailureReason.POST_REJECTED)));
        blockedTransferOpenFor(60);
        when(writer.tryApplyConfirmed(eq(TRANSFER_ID), any(Instant.class))).thenReturn(false);

        assertThatThrownBy(() -> service.confirmTransfer(TRANSFER_ID, SENDER_ID, CODE))
                .isInstanceOf(TransferNotBlockedException.class)
                .hasMessage("transfer " + TRANSFER_ID + " is not BLOCKED");
    }

    @Test
    void aConfirmationRefusedWhileTheTransferIsStillBlockedIsRefusedAsExpired() {
        when(transfers.findById(TRANSFER_ID)).thenReturn(Optional.of(blockedTransfer()));
        blockedTransferOpenFor(60);
        when(writer.tryApplyConfirmed(eq(TRANSFER_ID), any(Instant.class))).thenReturn(false);

        assertThatThrownBy(() -> service.confirmTransfer(TRANSFER_ID, SENDER_ID, CODE))
                .isInstanceOf(VerificationExpiredException.class)
                .hasMessage("the confirmation window of transfer " + TRANSFER_ID + " has closed");
    }

    @Test
    void aWrongCodeNeverReachesTheTransition() {
        when(transfers.findById(TRANSFER_ID)).thenReturn(Optional.of(blockedTransfer()));
        blockedTransferOpenFor(60);

        assertThatThrownBy(() -> service.confirmTransfer(TRANSFER_ID, SENDER_ID, "wrong"))
                .isInstanceOf(InvalidVerificationCodeException.class)
                .hasMessage("wrong verification code for transfer " + TRANSFER_ID);
        verify(writer, never()).tryApplyConfirmed(any(), any());
    }

    @Test
    void aBlockedTransferWithoutAnOpenWindowStepIsAnInconsistency() {
        when(transfers.findById(TRANSFER_ID)).thenReturn(Optional.of(blockedTransfer()));
        when(sagaSteps.findByTransferId(TRANSFER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.confirmTransfer(TRANSFER_ID, SENDER_ID, CODE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("blocked transfer " + TRANSFER_ID + " has no open confirmation window");
    }

    @Test
    void theWindowIsTheLatestRetryScreenStepWhateverOtherStepsFollowIt() {
        Transfer blocked = blockedTransfer();
        when(transfers.findById(TRANSFER_ID)).thenReturn(Optional.of(blocked));
        Instant now = Instant.now();
        when(sagaSteps.findByTransferId(TRANSFER_ID))
                .thenReturn(List.of(
                        new SagaStep(TRANSFER_ID, SagaStepName.RESERVE, SagaStepOutcome.OK, null, Instant.now()),
                        new SagaStep(
                                TRANSFER_ID,
                                SagaStepName.SCREEN,
                                SagaStepOutcome.RETRY,
                                null,
                                now.minusSeconds(600),
                                Instant.now()),
                        new SagaStep(
                                TRANSFER_ID,
                                SagaStepName.SCREEN,
                                SagaStepOutcome.RETRY,
                                null,
                                now.plusSeconds(60),
                                Instant.now()),
                        new SagaStep(
                                TRANSFER_ID,
                                SagaStepName.SCREEN,
                                SagaStepOutcome.OK,
                                "{\"verification\":\"SENDER_CONFIRMED\"}",
                                Instant.now()),
                        new SagaStep(
                                TRANSFER_ID,
                                SagaStepName.RELEASE,
                                SagaStepOutcome.RETRY,
                                null,
                                now.minusSeconds(10),
                                Instant.now())));
        when(writer.tryApplyConfirmed(eq(TRANSFER_ID), any(Instant.class))).thenReturn(true);

        Transfer confirmed = service.confirmTransfer(TRANSFER_ID, SENDER_ID, CODE);

        assertThat(confirmed).isSameAs(blocked);
        verify(writer).tryApplyConfirmed(eq(TRANSFER_ID), any(Instant.class));
    }

    @Test
    void anotherUserIsToldTheTransferDoesNotExist() {
        when(transfers.findById(TRANSFER_ID)).thenReturn(Optional.of(blockedTransfer()));

        assertThatThrownBy(() -> service.confirmTransfer(TRANSFER_ID, UUID.randomUUID(), CODE))
                .isInstanceOf(TransferNotFoundException.class);

        verify(writer, never()).tryApplyConfirmed(any(), any());
    }

    @ParameterizedTest(name = "\"{0}\" is refused")
    @ValueSource(strings = {"", " ", "\t"})
    void aBlankVerificationCodeRefusesTheServiceStart(String blank) {
        assertThatThrownBy(() -> new SagaService(
                        transfers,
                        sagaSteps,
                        mock(OutboxPort.class),
                        ledger,
                        new JsonMapper(),
                        writer,
                        Clock.systemUTC(),
                        SETTINGS,
                        blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sagapay.fraud.verification-code must not be blank");
    }
}
