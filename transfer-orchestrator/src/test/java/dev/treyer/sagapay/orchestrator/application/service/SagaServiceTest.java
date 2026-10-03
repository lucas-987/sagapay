package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.OutboxRepository;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.TransferRepository;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.SagaStepRepository;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, SagaServiceTest.FakeLedgerPortConfig.class})
@SpringBootTest
class SagaServiceTest {

    @Autowired
    private SagaService sagaService;
    @Autowired
    private TransferRepository transferRepository;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private SagaStepRepository sagaStepRepository;
    @Autowired
    private FakeLedgerPort fakeLedgerPort;
    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void resetFakeLedger() {
        fakeLedgerPort.reset();
    }

    private final UUID senderId = UUID.randomUUID();
    private final UUID senderAccountId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();
    private final UUID recipientAccountId = UUID.randomUUID();
    private final Money amount = Money.of("80.00", "EUR");

    private Transfer initiate(UUID idempotencyKey) {
        return sagaService.initiateTransfer(senderId, senderAccountId, recipientId, recipientAccountId,
                amount, idempotencyKey, null).transfer();
    }

    /** Parsed, not matched as text: Postgres reformats jsonb. */
    private String failedEventReasonFor(UUID transferId) {
        String payload = outboxRepository.findByAggregateIdOrderByCreatedAt(transferId).stream()
                .filter(row -> row.getEventType().equals("TransferFailed"))
                .map(OutboxRow::getPayload)
                .findFirst().orElseThrow();
        return jsonMapper.readTree(payload).get("reason").asString();
    }

    private List<String> eventTypesFor(UUID transferId) {
        return outboxRepository.findByAggregateIdOrderByCreatedAt(transferId).stream()
                .map(OutboxRow::getEventType).toList();
    }

    @Test
    void initiateTransferWritesInitiatedAndOutboxInOneTransaction() {
        Transfer transfer = initiate(UUID.randomUUID());

        assertThat(transferRepository.findById(transfer.getId()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.INITIATED);
        assertThat(eventTypesFor(transfer.getId())).containsExactly("TransferInitiated");
    }

    @Test
    void checkAndReserveOkChainsDirectlyToPostTransferNoFraud() {
        fakeLedgerPort.willReserve(new ReservationResult.Ok(UUID.randomUUID()));
        fakeLedgerPort.willPost(true);

        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());

        assertThat(transferRepository.findById(transfer.getId()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.POSTED);
        assertThat(eventTypesFor(transfer.getId()))
                .containsExactly("TransferInitiated", "FundsReserved", "TransferPosted");
        assertThat(fakeLedgerPort.postTransferCallCount()).isEqualTo(1);
    }

    @Test
    void insufficientFundsFailsWithoutEverCallingPostTransfer() {
        fakeLedgerPort.willReserve(new ReservationResult.InsufficientFunds());

        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());

        assertThat(transferRepository.findById(transfer.getId()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.FAILED);
        assertThat(fakeLedgerPort.postTransferCallCount()).isZero();
        assertThat(failedEventReasonFor(transfer.getId())).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void sameIdempotencyKeyTwiceReturnsTheExistingTransfer() {
        UUID idempotencyKey = UUID.randomUUID();

        var first = sagaService.initiateTransfer(senderId, senderAccountId, recipientId, recipientAccountId,
                amount, idempotencyKey, null);
        var second = sagaService.initiateTransfer(senderId, senderAccountId, recipientId, recipientAccountId,
                amount, idempotencyKey, null);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.transfer().getId()).isEqualTo(first.transfer().getId());
        assertThat(eventTypesFor(first.transfer().getId())).containsExactly("TransferInitiated");
    }

    @Test
    void continueFromReservedIsANoOpWhenTheTransferIsNotInReserved() {
        Transfer transfer = initiate(UUID.randomUUID()); // still INITIATED

        sagaService.continueFromReserved(transfer.getId());

        assertThat(transferRepository.findById(transfer.getId()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.INITIATED);
        assertThat(fakeLedgerPort.postTransferCallCount()).isZero();
    }

    @Test
    void reserveRejectedByTheLedgerFailsTheTransferInsteadOfLeavingItInitiated() {
        fakeLedgerPort.willFailReserve(new LedgerRejectedException("NOT_FOUND", "unknown account", null));

        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());

        Transfer after = transferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(after.getFailureReason()).isEqualTo("RESERVE_REJECTED");
        assertThat(eventTypesFor(transfer.getId())).containsExactly("TransferInitiated", "TransferFailed");
        assertThat(failedEventReasonFor(transfer.getId())).isEqualTo("RESERVE_REJECTED");
        assertThat(fakeLedgerPort.postTransferCallCount()).isZero();
    }

    @Test
    void postRejectedByTheLedgerFailsTheTransferInsteadOfLeavingItReserved() {
        fakeLedgerPort.willFailPost(new LedgerRejectedException("NOT_FOUND", "no matching reservation", null));

        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());

        Transfer after = transferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(after.getFailureReason()).isEqualTo("POST_REJECTED");
        assertThat(eventTypesFor(transfer.getId()))
                .containsExactly("TransferInitiated", "FundsReserved", "TransferFailed");
        assertThat(failedEventReasonFor(transfer.getId())).isEqualTo("POST_REJECTED");
        assertThat(sagaStepRepository.findByTransferIdOrderByAtAsc(transfer.getId()))
                .last()
                .satisfies(step -> {
                    assertThat(step.getStep()).isEqualTo("POST");
                    assertThat(step.getOutcome()).isEqualTo("FAILED");
                    assertThat(step.getDetail()).contains("NOT_FOUND").contains("no matching reservation");
                });
    }

    @Test
    void ledgerUnavailableLeavesTheTransferInitiatedForTheReprisePoller() {
        fakeLedgerPort.willFailReserve(new LedgerUnavailableException(new RuntimeException("deadline exceeded")));

        Transfer transfer = initiate(UUID.randomUUID());

        assertThatThrownBy(() -> sagaService.advance(transfer.getId()))
                .isInstanceOf(LedgerUnavailableException.class);
        assertThat(transferRepository.findById(transfer.getId()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.INITIATED);
        assertThat(eventTypesFor(transfer.getId())).containsExactly("TransferInitiated");
    }

    @TestConfiguration
    static class FakeLedgerPortConfig {
        // @Primary over the real gRPC adapter.
        @Bean
        @Primary
        FakeLedgerPort fakeLedgerPort() {
            return new FakeLedgerPort();
        }
    }
}
