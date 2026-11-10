package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.application.port.in.AdvanceSagaUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ConfirmTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.GetTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.InitiateTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ListTransfersUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.PostClearedTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ResolveRecipientUseCase;
import dev.treyer.sagapay.orchestrator.config.ResourceServerSecurityConfig;
import dev.treyer.sagapay.orchestrator.domain.InvalidVerificationCodeException;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.SagaStepName;
import dev.treyer.sagapay.orchestrator.domain.SagaStepOutcome;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferNotBlockedException;
import dev.treyer.sagapay.orchestrator.domain.TransferNotFoundException;
import dev.treyer.sagapay.orchestrator.domain.VerificationExpiredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A {@link SyncTaskExecutor} runs the background {@code advance()} inline, so the tests can
 * observe it. The caller is the subject of the token the request carries. */
@WebMvcTest(TransferRestAdapter.class)
@Import({TransferRestAdapterTest.SyncExecutorConfig.class, ResourceServerSecurityConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class TransferRestAdapterTest {

    private static final String VALID_BODY = """
            {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "80.00"}, "note": "pizza"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

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
    private PostClearedTransferUseCase postClearedTransferUseCase;

    @MockitoBean
    private ResolveRecipientUseCase resolveRecipientUseCase;

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
                "pizza",
                Instant.now());
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
                        .with(token(senderId))
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
                        .with(token(senderId))
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
                        .with(token(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    private static RequestPostProcessor token(UUID subject) {
        return jwt().jwt(jwt -> jwt.subject(subject.toString()));
    }

    @Test
    void createTransferTakesTheSenderFromTheTokenWhateverTheHeaderClaims() throws Exception {
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
                        .with(token(senderId))
                        .header("X-User-Id", UUID.randomUUID())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(recipientId)))
                .andExpect(status().isAccepted());
    }

    @Test
    void createTransferToARecipientNamedByHandleResolvesItAndSendsFromTheTokenOwner() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Transfer transfer = newTransfer(senderId, recipientId);
        when(resolveRecipientUseCase.resolveByHandle("@ines")).thenReturn(recipientId);
        when(initiateTransferUseCase.initiateTransfer(
                        eq(senderId),
                        eq(senderId),
                        eq(recipientId),
                        eq(recipientId),
                        any(Money.class),
                        any(UUID.class),
                        eq(null)))
                .thenReturn(new InitiateTransferUseCase.Result(transfer, true));

        mockMvc.perform(post("/v1/transfers")
                        .with(token(senderId))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toHandle": "@ines", "amount": {"currency": "EUR", "amount": "80.00"}}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.recipientId").value(recipientId.toString()));
    }

    @Test
    void createTransferWithoutATokenReturns401AndCreatesNothing() throws Exception {
        mockMvc.perform(post("/v1/transfers")
                        .header("X-User-Id", UUID.randomUUID())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());

        verify(initiateTransferUseCase, never()).initiateTransfer(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createTransferWithATokenWhoseSubjectIsNotAUuidReturns401AndCreatesNothing(CapturedOutput output)
            throws Exception {
        mockMvc.perform(post("/v1/transfers")
                        .with(jwt().jwt(jwt -> jwt.subject("not-a-uuid")))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().string(""));

        verify(initiateTransferUseCase, never()).initiateTransfer(any(), any(), any(), any(), any(), any(), any());
        assertThat(output.getOut()).contains("\"message\":\"request refused, caller not identified\"");
    }

    @Test
    void createTransferWithATokenWithoutSubjectReturns401AndCreatesNothing() throws Exception {
        mockMvc.perform(post("/v1/transfers")
                        .with(jwt().jwt(jwt -> jwt.claim("scope", "any").claims(claims -> claims.remove("sub"))))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().string(""));

        verify(initiateTransferUseCase, never()).initiateTransfer(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void getTransferWithATokenOfNeitherPartyReturns404() throws Exception {
        UUID transferId = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        when(getTransferUseCase.getTransfer(transferId, stranger)).thenThrow(new TransferNotFoundException(transferId));

        mockMvc.perform(get("/v1/transfers/{id}", transferId).with(token(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void confirmWithATokenOfTheRecipientReturns404AndPostsNothing() throws Exception {
        UUID transferId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        when(confirmTransferUseCase.confirmTransfer(transferId, recipient, "tok"))
                .thenThrow(new TransferNotFoundException(transferId));

        mockMvc.perform(post("/v1/transfers/{id}/confirm", transferId)
                        .with(token(recipient))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRM_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));

        verify(postClearedTransferUseCase, never()).postCleared(any());
    }

    private static final String CONFIRM_BODY = """
            {"verificationToken": "tok"}""";

    private static final UUID CALLER_ID = UUID.fromString("0a1b2c3d-0000-4000-8000-0000000000aa");

    private org.springframework.test.web.servlet.ResultActions confirm(UUID transferId) throws Exception {
        return mockMvc.perform(post("/v1/transfers/{id}/confirm", transferId)
                .with(token(CALLER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(CONFIRM_BODY));
    }

    private static String confirmProblem(int status, String title, String detail, String code, UUID transferId) {
        return """
                {"title": "%s", "status": %d, "detail": "%s",
                 "instance": "/v1/transfers/%s/confirm", "code": "%s"}""".formatted(title, status, detail, transferId, code);
    }

    @Test
    void confirmBlockedTransferReturns202AndPostsTheTransferInTheBackground() throws Exception {
        Transfer transfer = newTransfer(UUID.randomUUID(), UUID.randomUUID());
        when(confirmTransferUseCase.confirmTransfer(transfer.getId(), CALLER_ID, "tok"))
                .thenReturn(transfer);

        confirm(transfer.getId())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(transfer.getId().toString()));

        verify(postClearedTransferUseCase).postCleared(transfer.getId());
    }

    @Test
    void aLedgerUnavailableWhilePostingDoesNotChangeTheAcceptedAnswer() throws Exception {
        Transfer transfer = newTransfer(UUID.randomUUID(), UUID.randomUUID());
        when(confirmTransferUseCase.confirmTransfer(transfer.getId(), CALLER_ID, "tok"))
                .thenReturn(transfer);
        org.mockito.Mockito.doThrow(new LedgerUnavailableException(new RuntimeException("down")))
                .when(postClearedTransferUseCase)
                .postCleared(transfer.getId());

        confirm(transfer.getId()).andExpect(status().isAccepted());
    }

    @Test
    void confirmingAnUnknownTransferReturns404WithTransferNotFoundCode() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(confirmTransferUseCase.confirmTransfer(transferId, CALLER_ID, "tok"))
                .thenThrow(new TransferNotFoundException(transferId));

        confirm(transferId)
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content()
                        .json(
                                confirmProblem(
                                        404,
                                        "Not Found",
                                        "unknown transfer " + transferId,
                                        "TRANSFER_NOT_FOUND",
                                        transferId),
                                true));
        verify(postClearedTransferUseCase, never()).postCleared(any());
    }

    @Test
    void confirmingATransferNotBlockedReturns409WithTransferNotBlockedCode() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(confirmTransferUseCase.confirmTransfer(transferId, CALLER_ID, "tok"))
                .thenThrow(new TransferNotBlockedException(transferId));

        confirm(transferId)
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content()
                        .json(
                                confirmProblem(
                                        409,
                                        "Conflict",
                                        "transfer " + transferId + " is not BLOCKED",
                                        "TRANSFER_NOT_BLOCKED",
                                        transferId),
                                true));
        verify(postClearedTransferUseCase, never()).postCleared(any());
    }

    @Test
    void confirmingAfterTheWindowClosedReturns410WithVerificationExpiredCode() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(confirmTransferUseCase.confirmTransfer(transferId, CALLER_ID, "tok"))
                .thenThrow(new VerificationExpiredException(transferId));

        confirm(transferId)
                .andExpect(status().isGone())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content()
                        .json(
                                confirmProblem(
                                        410,
                                        "Gone",
                                        "the confirmation window of transfer " + transferId + " has closed",
                                        "VERIFICATION_EXPIRED",
                                        transferId),
                                true));
        verify(postClearedTransferUseCase, never()).postCleared(any());
    }

    @Test
    void confirmingWithAWrongCodeReturns422WithInvalidVerificationCodeCode() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(confirmTransferUseCase.confirmTransfer(transferId, CALLER_ID, "tok"))
                .thenThrow(new InvalidVerificationCodeException(transferId));

        confirm(transferId)
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content()
                        .json(
                                confirmProblem(
                                        422,
                                        "Unprocessable Content",
                                        "wrong verification code for transfer " + transferId,
                                        "INVALID_VERIFICATION_CODE",
                                        transferId),
                                true));
        verify(postClearedTransferUseCase, never()).postCleared(any());
    }

    @Test
    void getTransferWithoutATokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/transfers/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());

        verify(getTransferUseCase, never()).getTransfer(any(), any());
    }

    @Test
    void confirmWithoutATokenReturns401AndConfirmsNothing() throws Exception {
        mockMvc.perform(post("/v1/transfers/{id}/confirm", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRM_BODY))
                .andExpect(status().isUnauthorized());

        verify(confirmTransferUseCase, never()).confirmTransfer(any(), any(), any());
    }

    @Test
    void listTransfersWithoutATokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/transfers")).andExpect(status().isUnauthorized());

        verify(listTransfersUseCase, never()).listTransfers(any(), any(), any(), any(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 201})
    void listTransfersRefusesALimitOutOfBounds(int limit) throws Exception {
        mockMvc.perform(get("/v1/transfers").with(token(CALLER_ID)).param("limit", String.valueOf(limit)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verify(listTransfersUseCase, never()).listTransfers(any(), any(), any(), any(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 200})
    void listTransfersAcceptsTheBoundsOfTheLimit(int limit) throws Exception {
        when(listTransfersUseCase.listTransfers(any(), any(), any(), any(), eq(limit)))
                .thenReturn(new ListTransfersUseCase.Page(List.of(), null));

        mockMvc.perform(get("/v1/transfers").with(token(CALLER_ID)).param("limit", String.valueOf(limit)))
                .andExpect(status().isOk());
    }

    @Test
    void listTransfersRefusesAnUnknownDirection() throws Exception {
        mockMvc.perform(get("/v1/transfers").with(token(CALLER_ID)).param("direction", "foo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void getTransferUnknownIdReturns404WithTransferNotFoundCode() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(getTransferUseCase.getTransfer(transferId, CALLER_ID))
                .thenThrow(new TransferNotFoundException(transferId));

        mockMvc.perform(get("/v1/transfers/{id}", transferId).with(token(CALLER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void getTransferReturnsDetailWithSteps() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Transfer transfer = newTransfer(senderId, recipientId);
        when(getTransferUseCase.getTransfer(transfer.getId(), CALLER_ID))
                .thenReturn(new GetTransferUseCase.TransferWithSteps(transfer, List.of()));

        mockMvc.perform(get("/v1/transfers/{id}", transfer.getId()).with(token(CALLER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transfer.getId().toString()));
    }

    @ParameterizedTest
    @EnumSource(Party.class)
    @Tag("M3-S22")
    @DisplayName("A transfer never shows the outcome of its fraud screening")
    void transferNeverShowsItsFraudResult(Party party) throws Exception {
        UUID senderId = party == Party.SENDER ? CALLER_ID : UUID.randomUUID();
        UUID recipientId = party == Party.RECIPIENT ? CALLER_ID : UUID.randomUUID();
        Transfer flagged = newTransfer(senderId, recipientId);
        SagaStep screen = new SagaStep(
                flagged.getId(),
                SagaStepName.SCREEN,
                SagaStepOutcome.RETRY,
                "{\"decision\":\"FLAGGED\",\"score\":0.9,\"reasons\":[\"NEW_RECIPIENT\"]}",
                Instant.now());
        when(getTransferUseCase.getTransfer(flagged.getId(), CALLER_ID))
                .thenReturn(new GetTransferUseCase.TransferWithSteps(flagged, List.of(screen)));

        mockMvc.perform(get("/v1/transfers/{id}", flagged.getId()).with(token(CALLER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].step").value("SCREEN"))
                .andExpect(jsonPath("$.fraud").doesNotExist())
                .andExpect(jsonPath("$.steps[0].detail").doesNotExist())
                .andExpect(content().string(not(containsString("NEW_RECIPIENT"))))
                .andExpect(content().string(not(containsString("FLAGGED"))));
    }

    private enum Party {
        SENDER,
        RECIPIENT
    }

    @Test
    void anUnexpectedFailureIsAProblemWithoutItsInternalMessage() throws Exception {
        UUID id = UUID.randomUUID();
        when(getTransferUseCase.getTransfer(id, CALLER_ID)).thenThrow(new IllegalStateException("secret internals"));

        mockMvc.perform(get("/v1/transfers/{id}", id).with(token(CALLER_ID)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("secret internals"))));
    }

    @Test
    void aMissingRequiredHeaderStaysA400() throws Exception {
        mockMvc.perform(post("/v1/transfers")
                        .with(token(CALLER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "10.00"}}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @TestConfiguration
    static class SyncExecutorConfig {
        @Bean("applicationTaskExecutor")
        Executor applicationTaskExecutor() {
            return new SyncTaskExecutor();
        }
    }
}
