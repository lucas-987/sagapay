package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.application.port.in.AdvanceSagaUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ConfirmTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.GetTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.InitiateTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ListTransfersUseCase;
import dev.treyer.sagapay.orchestrator.application.port.out.AccountLookupPort;
import dev.treyer.sagapay.orchestrator.domain.FraudDecision;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferNotBlockedException;
import dev.treyer.sagapay.orchestrator.domain.TransferNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Security is tested separately. A {@link SyncTaskExecutor} runs the background
 * {@code advance()} inline, so the tests can observe it. */
@WebMvcTest(TransferRestAdapter.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TransferRestAdapterTest.SyncExecutorConfig.class)
class TransferRestAdapterTest {

    private static final String VALID_BODY = """
            {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "80.00"}, "note": "pizza"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InitiateTransferUseCase initiateTransferUseCase;

    @MockitoBean
    private AdvanceSagaUseCase advanceSagaUseCase;

    @MockitoBean
    private ListTransfersUseCase listTransfersUseCase;

    @MockitoBean
    private GetTransferUseCase getTransferUseCase;

    @MockitoBean
    private ConfirmTransferUseCase confirmTransferUseCase;

    @MockitoBean
    private AccountLookupPort accountLookupPort;

    private static Transfer newTransfer(UUID senderId, UUID recipientId) {
        return new Transfer(
                UUID.randomUUID(),
                UUID.randomUUID(),
                senderId,
                senderId,
                recipientId,
                recipientId,
                new BigDecimal("80.0000"),
                "EUR",
                "pizza");
    }

    @Test
    void createTransferReturns202AndDispatchesAdvanceWhenNewlyCreated() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Transfer transfer = newTransfer(senderId, recipientId);
        when(initiateTransferUseCase.initiateTransfer(
                        eq(senderId),
                        eq(senderId),
                        eq(recipientId),
                        eq(recipientId),
                        any(Money.class),
                        any(UUID.class),
                        eq("pizza")))
                .thenReturn(new InitiateTransferUseCase.Result(transfer, true));

        mockMvc.perform(post("/v1/transfers")
                        .header("X-User-Id", senderId)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(recipientId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(transfer.getId().toString()))
                .andExpect(jsonPath("$.status").value("INITIATED"));

        verify(advanceSagaUseCase).advance(transfer.getId());
    }

    @Test
    void createTransferReturns409AndDoesNotDispatchAdvanceOnReplay() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Transfer transfer = newTransfer(senderId, recipientId);
        when(initiateTransferUseCase.initiateTransfer(
                        eq(senderId),
                        eq(senderId),
                        eq(recipientId),
                        eq(recipientId),
                        any(Money.class),
                        any(UUID.class),
                        eq("pizza")))
                .thenReturn(new InitiateTransferUseCase.Result(transfer, false));

        mockMvc.perform(post("/v1/transfers")
                        .header("X-User-Id", senderId)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(recipientId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.id").value(transfer.getId().toString()));

        verify(advanceSagaUseCase, never()).advance(any());
    }

    @Test
    void createTransferWithoutIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/v1/transfers")
                        .header("X-User-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTransferWithoutUserIdHeaderReturns400WithMalformedRequestCode() throws Exception {
        mockMvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void confirmBlockedTransferAlwaysReturns409WithTransferNotBlockedCode() throws Exception {
        UUID transferId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new TransferNotBlockedException(transferId))
                .when(confirmTransferUseCase)
                .confirmTransfer(eq(transferId), eq("tok"));

        mockMvc.perform(post("/v1/transfers/{id}/confirm", transferId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationToken": "tok"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_BLOCKED"));
    }

    @Test
    void getTransferUnknownIdReturns404WithTransferNotFoundCode() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(getTransferUseCase.getTransfer(transferId)).thenThrow(new TransferNotFoundException(transferId));

        mockMvc.perform(get("/v1/transfers/{id}", transferId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void getTransferReturnsDetailWithSteps() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Transfer transfer = newTransfer(senderId, recipientId);
        when(getTransferUseCase.getTransfer(transfer.getId()))
                .thenReturn(new GetTransferUseCase.TransferWithSteps(transfer, List.of(), null));

        mockMvc.perform(get("/v1/transfers/{id}", transfer.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transfer.getId().toString()));
    }

    @Test
    @Tag("M3-S22")
    @DisplayName("The transfer shows its fraud result once screened")
    void transferShowsItsFraudResultOnceScreened() throws Exception {
        UUID senderId = UUID.randomUUID();
        Transfer notScreened = newTransfer(senderId, UUID.randomUUID());
        Transfer cleared = newTransfer(senderId, UUID.randomUUID());
        Transfer flagged = newTransfer(senderId, UUID.randomUUID());
        when(getTransferUseCase.getTransfer(notScreened.getId()))
                .thenReturn(new GetTransferUseCase.TransferWithSteps(notScreened, List.of(), null));
        when(getTransferUseCase.getTransfer(cleared.getId()))
                .thenReturn(new GetTransferUseCase.TransferWithSteps(
                        cleared,
                        List.of(),
                        new GetTransferUseCase.ScreeningVerdict(
                                FraudDecision.CLEARED, new BigDecimal("0.1500"), List.of("AMOUNT_ROUND"))));
        when(getTransferUseCase.getTransfer(flagged.getId()))
                .thenReturn(new GetTransferUseCase.TransferWithSteps(
                        flagged,
                        List.of(),
                        new GetTransferUseCase.ScreeningVerdict(
                                FraudDecision.FLAGGED,
                                new BigDecimal("0.9000"),
                                List.of("VELOCITY_1H", "NEW_RECIPIENT"))));

        mockMvc.perform(get("/v1/transfers/{id}", notScreened.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fraud").value(nullValue()));
        mockMvc.perform(get("/v1/transfers/{id}", cleared.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fraud.decision").value("CLEARED"))
                .andExpect(jsonPath("$.fraud.score").value(0.15))
                .andExpect(jsonPath("$.fraud.reasons").value(contains("AMOUNT_ROUND")));
        mockMvc.perform(get("/v1/transfers/{id}", flagged.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fraud.decision").value("FLAGGED"))
                .andExpect(jsonPath("$.fraud.score").value(0.9))
                .andExpect(jsonPath("$.fraud.reasons").value(contains("VELOCITY_1H", "NEW_RECIPIENT")));
    }

    @TestConfiguration
    static class SyncExecutorConfig {
        @Bean("applicationTaskExecutor")
        Executor applicationTaskExecutor() {
            return new SyncTaskExecutor();
        }
    }
}
