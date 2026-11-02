package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.OutboxRepository;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.SagaStepRepository;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.TransferRepository;
import dev.treyer.sagapay.orchestrator.application.port.in.GetTransferUseCase;
import dev.treyer.sagapay.orchestrator.domain.FraudDecision;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SagaTransitionWriter writer;

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
        return sagaService
                .initiateTransfer(
                        senderId, senderAccountId, recipientId, recipientAccountId, amount, idempotencyKey, null)
                .transfer();
    }

    /** Reserved, then cleared by fraud, without posting. */
    private Transfer clearedTransfer() {
        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());
        writer.applyCleared(cleared(transfer.getId(), UUID.randomUUID()));
        return transfer;
    }

    private int processedEventCount(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from processed_events where event_id = ?", Integer.class, eventId);
    }

    private static FraudResult cleared(UUID transferId, UUID eventId) {
        return new FraudResult(
                eventId, transferId, FraudDecision.CLEARED, new BigDecimal("0.1500"), List.of("AMOUNT_ROUND"));
    }

    /** Parsed, not matched as text: Postgres reformats jsonb. */
    private String failedEventReasonFor(UUID transferId) {
        String payload = outboxRepository.findByAggregateIdOrderByCreatedAt(transferId).stream()
                .filter(row -> row.getEventType().equals("TransferFailed"))
                .map(OutboxRow::getPayload)
                .findFirst()
                .orElseThrow();
        return jsonMapper.readTree(payload).get("reason").asString();
    }

    private List<String> eventTypesFor(UUID transferId) {
        return outboxRepository.findByAggregateIdOrderByCreatedAt(transferId).stream()
                .map(OutboxRow::getEventType)
                .toList();
    }

    @Test
    void initiateTransferWritesInitiatedAndOutboxInOneTransaction() {
        Transfer transfer = initiate(UUID.randomUUID());

        assertThat(transferRepository.findById(transfer.getId()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.INITIATED);
        assertThat(eventTypesFor(transfer.getId())).containsExactly("TransferInitiated");
    }

    @Test
    @Tag("M3-S01")
    @DisplayName("A reserved transfer waits for its screening result before any money moves")
    void reservedTransferWaitsForItsScreeningResult() {
        UUID reservationId = UUID.randomUUID();
        fakeLedgerPort.willReserve(new ReservationResult.Ok(reservationId));
        Transfer transfer = initiate(UUID.randomUUID());

        Instant before = Instant.now();
        sagaService.advance(transfer.getId());
        Instant after = Instant.now();

        Transfer reloaded = transferRepository.findById(transfer.getId()).orElseThrow();
        List<SagaStep> steps = sagaStepRepository.findByTransferIdOrderByAtAscIdAsc(transfer.getId());
        // The column keeps microseconds: one microsecond of rounding either way.
        assertThat(steps)
                .filteredOn(step -> step.getStep().equals("SCREEN"))
                .singleElement()
                .satisfies(screen -> assertThat(screen.getDeadline())
                        .isBetween(
                                before.plusMillis(10_000).minus(1, ChronoUnit.MICROS),
                                after.plusMillis(10_000).plus(1, ChronoUnit.MICROS)));
        assertThat(reloaded.getStatus()).isEqualTo(TransferStatus.SCREENING);
        assertThat(reloaded.getReservationId()).isEqualTo(reservationId);
        assertThat(stepsFor(transfer.getId())).containsExactly("RESERVE OK", "SCREEN RETRY");
        assertThat(eventTypesFor(transfer.getId())).containsExactly("TransferInitiated", "FundsReserved");
        assertThat(fakeLedgerPort.postTransferCallCount()).isZero();
    }

    @Test
    @Tag("M3-S02")
    @DisplayName("A cleared transfer is posted once")
    void clearedTransferIsPostedOnce() {
        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());

        sagaService.applyFraudResult(new FraudResult(
                UUID.randomUUID(),
                transfer.getId(),
                FraudDecision.CLEARED,
                new BigDecimal("0.1500"),
                List.of("AMOUNT_ROUND", "NEW_RECIPIENT")));

        Transfer after = transferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TransferStatus.POSTED);
        assertThat(after.getFraudScore()).isEqualByComparingTo("0.1500");
        assertThat(after.getFraudReasons()).containsExactly("AMOUNT_ROUND", "NEW_RECIPIENT");
        assertThat(fakeLedgerPort.postTransferCallCount()).isEqualTo(1);
        assertThat(eventTypesFor(transfer.getId()))
                .containsExactly("TransferInitiated", "FundsReserved", "TransferPosted");
        assertThat(stepsFor(transfer.getId())).containsExactly("RESERVE OK", "SCREEN RETRY", "SCREEN OK", "POST OK");
        assertThat(sagaStepRepository.findByTransferIdOrderByAtAscIdAsc(transfer.getId()))
                .filteredOn(step ->
                        step.getStep().equals("SCREEN") && step.getOutcome().equals("OK"))
                .singleElement()
                .satisfies(step ->
                        assertThat(jsonMapper.readTree(step.getDetail())).isEqualTo(jsonMapper.readTree("""
                                {"decision": "CLEARED", "score": 0.1500, "reasons": ["AMOUNT_ROUND", "NEW_RECIPIENT"]}""")));
    }

    @Test
    void theSameResultAppliedTwiceIsAppliedOnce() {
        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());
        FraudResult result = cleared(transfer.getId(), UUID.randomUUID());

        sagaService.applyFraudResult(result);
        sagaService.applyFraudResult(result);

        assertThat(fakeLedgerPort.postTransferCallCount()).isEqualTo(1);
        assertThat(eventTypesFor(transfer.getId())).containsOnlyOnce("TransferPosted");
    }

    @Test
    void aResultForATransferNoLongerScreenedIsRecordedAndNothingElseHappens() {
        Transfer transfer = initiate(UUID.randomUUID()); // INITIATED, never reserved
        UUID eventId = UUID.randomUUID();

        sagaService.applyFraudResult(cleared(transfer.getId(), eventId));

        assertThat(transferRepository.findById(transfer.getId()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.INITIATED);
        assertThat(stepsFor(transfer.getId())).isEmpty();
        assertThat(fakeLedgerPort.postTransferCallCount()).isZero();
        assertThat(processedEventCount(eventId)).isEqualTo(1);
    }

    private static FraudResult flagged(UUID transferId, UUID eventId) {
        return new FraudResult(
                eventId,
                transferId,
                FraudDecision.FLAGGED,
                new BigDecimal("0.9000"),
                List.of("VELOCITY_1H", "NEW_RECIPIENT"));
    }

    @Test
    @Tag("M3-S21")
    @DisplayName("A flagged transfer is blocked with its money held")
    void flaggedTransferIsBlockedWithItsMoneyHeld() {
        UUID reservationId = UUID.randomUUID();
        fakeLedgerPort.willReserve(new ReservationResult.Ok(reservationId));
        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());

        Instant before = Instant.now();
        sagaService.applyFraudResult(flagged(transfer.getId(), UUID.randomUUID()));
        Instant after = Instant.now();

        Transfer reloaded = transferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransferStatus.BLOCKED);
        assertThat(reloaded.getReservationId()).isEqualTo(reservationId);
        assertThat(reloaded.getFraudScore()).isEqualByComparingTo("0.9000");
        assertThat(reloaded.getFraudReasons()).containsExactly("VELOCITY_1H", "NEW_RECIPIENT");
        assertThat(stepsFor(transfer.getId())).containsExactly("RESERVE OK", "SCREEN RETRY", "SCREEN RETRY");
        SagaStep blockStep = sagaStepRepository.findByTransferIdOrderByAtAscIdAsc(transfer.getId()).stream()
                .filter(step -> step.getDetail() != null)
                .findFirst()
                .orElseThrow();
        // The column keeps microseconds: one microsecond of rounding either way.
        assertThat(blockStep.getDeadline())
                .isBetween(
                        before.plusMillis(120_000).minus(1, ChronoUnit.MICROS),
                        after.plusMillis(120_000).plus(1, ChronoUnit.MICROS));
        assertThat(jsonMapper.readTree(blockStep.getDetail())).isEqualTo(jsonMapper.readTree("""
                {"decision": "FLAGGED", "score": 0.9000, "reasons": ["VELOCITY_1H", "NEW_RECIPIENT"]}"""));
        assertThat(fakeLedgerPort.postTransferCallCount()).isZero();
        assertThat(fakeLedgerPort.releaseCalls()).isEmpty();
        assertThat(eventTypesFor(transfer.getId())).containsExactly("TransferInitiated", "FundsReserved");
    }

    @Test
    void aTransferNotScreenedYetHasNoVerdict() {
        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());

        assertThat(sagaService.getTransfer(transfer.getId()).fraud()).isNull();
    }

    @Test
    void aClearedTransferHasItsVerdict() {
        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());
        sagaService.applyFraudResult(cleared(transfer.getId(), UUID.randomUUID()));

        assertThat(sagaService.getTransfer(transfer.getId()).fraud())
                .isEqualTo(new GetTransferUseCase.ScreeningVerdict(
                        FraudDecision.CLEARED, new BigDecimal("0.1500"), List.of("AMOUNT_ROUND")));
    }

    @Test
    void aScoredTransferWithoutAVerdictStepIsAnInconsistency() {
        Transfer transfer = initiate(UUID.randomUUID());
        jdbcTemplate.update("update transfers set fraud_score = 0.5 where id = ?", transfer.getId());

        assertThatThrownBy(() -> sagaService.getTransfer(transfer.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("screened transfer " + transfer.getId() + " has no verdict step");
    }

    @Test
    void aScreenStepWithoutADecisionIsAnInconsistency() {
        Transfer transfer = initiate(UUID.randomUUID());
        jdbcTemplate.update("update transfers set fraud_score = 0.5 where id = ?", transfer.getId());
        sagaStepRepository.save(new SagaStep(transfer.getId(), "SCREEN", "OK", "{}"));

        assertThatThrownBy(() -> sagaService.getTransfer(transfer.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("screened transfer " + transfer.getId() + " has no verdict step");
    }

    @Test
    void aFlaggedTransferHasItsVerdict() {
        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());
        sagaService.applyFraudResult(flagged(transfer.getId(), UUID.randomUUID()));

        assertThat(sagaService.getTransfer(transfer.getId()).fraud())
                .isEqualTo(new GetTransferUseCase.ScreeningVerdict(
                        FraudDecision.FLAGGED, new BigDecimal("0.9000"), List.of("VELOCITY_1H", "NEW_RECIPIENT")));
    }

    @Test
    void theSameFlaggedResultAppliedTwiceIsHandledOnce() {
        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());
        FraudResult result = flagged(transfer.getId(), UUID.randomUUID());

        sagaService.applyFraudResult(result);
        sagaService.applyFraudResult(result);

        assertThat(stepsFor(transfer.getId())).containsExactly("RESERVE OK", "SCREEN RETRY", "SCREEN RETRY");
        assertThat(transferRepository.findById(transfer.getId()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.BLOCKED);
    }

    @Test
    void aClearedResultAfterAFlaggedOneChangesNothing() {
        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());
        sagaService.applyFraudResult(flagged(transfer.getId(), UUID.randomUUID()));
        UUID laterEventId = UUID.randomUUID();

        sagaService.applyFraudResult(cleared(transfer.getId(), laterEventId));

        Transfer reloaded = transferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransferStatus.BLOCKED);
        assertThat(reloaded.getFraudScore()).isEqualByComparingTo("0.9000");
        assertThat(stepsFor(transfer.getId())).containsExactly("RESERVE OK", "SCREEN RETRY", "SCREEN RETRY");
        assertThat(fakeLedgerPort.postTransferCallCount()).isZero();
        assertThat(processedEventCount(laterEventId)).isEqualTo(1);
    }

    @Test
    void aFlaggedResultAfterAClearedOneChangesNothing() {
        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());
        sagaService.applyFraudResult(cleared(transfer.getId(), UUID.randomUUID()));
        UUID laterEventId = UUID.randomUUID();

        sagaService.applyFraudResult(flagged(transfer.getId(), laterEventId));

        Transfer reloaded = transferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransferStatus.POSTED);
        assertThat(reloaded.getFraudScore()).isEqualByComparingTo("0.1500");
        assertThat(stepsFor(transfer.getId())).containsExactly("RESERVE OK", "SCREEN RETRY", "SCREEN OK", "POST OK");
        assertThat(fakeLedgerPort.postTransferCallCount()).isEqualTo(1);
        assertThat(processedEventCount(laterEventId)).isEqualTo(1);
    }

    @Test
    void aFlaggedResultForATransferNoLongerScreenedIsRecordedAndIgnored() {
        Transfer transfer = initiate(UUID.randomUUID()); // INITIATED, never reserved
        UUID eventId = UUID.randomUUID();

        sagaService.applyFraudResult(flagged(transfer.getId(), eventId));

        Transfer reloaded = transferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransferStatus.INITIATED);
        assertThat(reloaded.getFraudScore()).isNull();
        assertThat(stepsFor(transfer.getId())).isEmpty();
        assertThat(processedEventCount(eventId)).isEqualTo(1);
    }

    @Test
    void ledgerUnavailableAtPostingLeavesTheClearedTransferForThePoller() {
        fakeLedgerPort.willFailPost(new LedgerUnavailableException(new RuntimeException("deadline exceeded")));
        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());

        sagaService.applyFraudResult(cleared(transfer.getId(), UUID.randomUUID()));

        assertThat(transferRepository.findById(transfer.getId()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.CLEARED);
        assertThat(eventTypesFor(transfer.getId())).containsExactly("TransferInitiated", "FundsReserved");
        assertThat(stepsFor(transfer.getId())).containsExactly("RESERVE OK", "SCREEN RETRY", "SCREEN OK");
    }

    @Test
    void reservingATransferAlreadyMovedOnChangesNothing() {
        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId()); // SCREENING

        boolean applied = writer.applyReserved(transfer.getId(), transfer, UUID.randomUUID());

        assertThat(applied).isFalse();
        assertThat(stepsFor(transfer.getId())).containsExactly("RESERVE OK", "SCREEN RETRY");
        assertThat(eventTypesFor(transfer.getId())).containsExactly("TransferInitiated", "FundsReserved");
    }

    @Test
    void postingATransferThatIsNotClearedChangesNothing() {
        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId()); // SCREENING

        boolean applied = writer.applyPosted(transfer.getId(), transfer);

        assertThat(applied).isFalse();
        assertThat(transferRepository.findById(transfer.getId()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.SCREENING);
        assertThat(stepsFor(transfer.getId())).containsExactly("RESERVE OK", "SCREEN RETRY");
        assertThat(eventTypesFor(transfer.getId())).doesNotContain("TransferPosted");
    }

    @Test
    void aClearedTransferTheLedgerDoesNotPostStaysClearedForThePoller() {
        fakeLedgerPort.willPost(false);
        Transfer transfer = clearedTransfer();

        sagaService.postCleared(transfer.getId());

        assertThat(transferRepository.findById(transfer.getId()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.CLEARED);
        assertThat(eventTypesFor(transfer.getId())).doesNotContain("TransferPosted");
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

        var first = sagaService.initiateTransfer(
                senderId, senderAccountId, recipientId, recipientAccountId, amount, idempotencyKey, null);
        var second = sagaService.initiateTransfer(
                senderId, senderAccountId, recipientId, recipientAccountId, amount, idempotencyKey, null);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.transfer().getId()).isEqualTo(first.transfer().getId());
        assertThat(eventTypesFor(first.transfer().getId())).containsExactly("TransferInitiated");
    }

    @Test
    void postClearedIsANoOpWhenTheTransferIsNotCleared() {
        Transfer transfer = initiate(UUID.randomUUID()); // still INITIATED

        sagaService.postCleared(transfer.getId());

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

    private List<String> stepsFor(UUID transferId) {
        return sagaStepRepository.findByTransferIdOrderByAtAscIdAsc(transferId).stream()
                .map(step -> step.getStep() + " " + step.getOutcome())
                .toList();
    }

    @Test
    @Tag("M3-S34")
    @DisplayName("A refused posting fails the transfer and releases its reservation")
    void postRejectedByTheLedgerFailsTheTransferAndReleasesTheReservation() {
        UUID reservationId = UUID.randomUUID();
        fakeLedgerPort.willReserve(new ReservationResult.Ok(reservationId));
        fakeLedgerPort.willFailPost(new LedgerRejectedException("NOT_FOUND", "no matching reservation", null));
        Transfer transfer = clearedTransfer();

        sagaService.postCleared(transfer.getId());

        Transfer after = transferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(after.getFailureReason()).isEqualTo("POST_REJECTED");
        assertThat(eventTypesFor(transfer.getId()))
                .containsExactly("TransferInitiated", "FundsReserved", "TransferFailed");
        assertThat(failedEventReasonFor(transfer.getId())).isEqualTo("POST_REJECTED");
        assertThat(fakeLedgerPort.releaseCalls()).containsExactly(reservationId);
        assertThat(stepsFor(transfer.getId()))
                .containsExactly(
                        "RESERVE OK",
                        "SCREEN RETRY",
                        "SCREEN OK",
                        "POST FAILED",
                        "RELEASE RETRY",
                        "RELEASE COMPENSATED");
        assertThat(sagaStepRepository.findByTransferIdOrderByAtAscIdAsc(transfer.getId()))
                .filteredOn(step -> step.getStep().equals("POST"))
                .singleElement()
                .satisfies(step ->
                        assertThat(step.getDetail()).contains("NOT_FOUND").contains("no matching reservation"));
    }

    @Test
    void releaseUnavailableLeavesTheFailedTransferWithAPendingRelease() {
        fakeLedgerPort.willFailPost(new LedgerRejectedException("NOT_FOUND", "no matching reservation", null));
        fakeLedgerPort.willFailRelease(new LedgerUnavailableException(new RuntimeException("deadline exceeded")));

        Transfer transfer = clearedTransfer();

        assertThatThrownBy(() -> sagaService.postCleared(transfer.getId()))
                .isInstanceOf(LedgerUnavailableException.class);
        assertThat(transferRepository.findById(transfer.getId()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.FAILED);
        assertThat(eventTypesFor(transfer.getId())).containsOnlyOnce("TransferFailed");
        assertThat(stepsFor(transfer.getId())).last().isEqualTo("RELEASE RETRY");
    }

    @Test
    void releaseRefusedEndsTheStepWithTheLedgersAnswer() {
        fakeLedgerPort.willFailPost(new LedgerRejectedException("NOT_FOUND", "no matching reservation", null));
        fakeLedgerPort.willFailRelease(new LedgerRejectedException("INVALID_ARGUMENT", "bad reservation id", null));

        Transfer transfer = clearedTransfer();
        sagaService.postCleared(transfer.getId());

        assertThat(transferRepository.findById(transfer.getId()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.FAILED);
        assertThat(stepsFor(transfer.getId()))
                .containsExactly(
                        "RESERVE OK", "SCREEN RETRY", "SCREEN OK", "POST FAILED", "RELEASE RETRY", "RELEASE FAILED");
        assertThat(sagaStepRepository.findByTransferIdOrderByAtAscIdAsc(transfer.getId()))
                .last()
                .satisfies(step -> assertThat(step.getDetail())
                        .contains("INVALID_ARGUMENT")
                        .contains("bad reservation id"));
    }

    @Test
    void failAndReleaseDoesNothingWhenAnotherActorMovedTheTransferFirst() {
        Transfer transfer = clearedTransfer();
        sagaService.postCleared(transfer.getId()); // POSTED

        boolean applied = writer.applyRejectedAndRelease(
                transfer.getId(),
                transfer,
                TransferStatus.CLEARED,
                "POST",
                "POST_REJECTED",
                new LedgerRejectedException("NOT_FOUND", "gone", null));

        assertThat(applied).isFalse();
        assertThat(eventTypesFor(transfer.getId())).doesNotContain("TransferFailed");
        assertThat(stepsFor(transfer.getId())).containsExactly("RESERVE OK", "SCREEN RETRY", "SCREEN OK", "POST OK");
    }

    @Test
    void ledgerUnavailableLeavesTheTransferInitiatedForTheReprisePoller() {
        fakeLedgerPort.willFailReserve(new LedgerUnavailableException(new RuntimeException("deadline exceeded")));

        Transfer transfer = initiate(UUID.randomUUID());

        assertThatThrownBy(() -> sagaService.advance(transfer.getId())).isInstanceOf(LedgerUnavailableException.class);
        assertThat(transferRepository.findById(transfer.getId()).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.INITIATED);
        assertThat(eventTypesFor(transfer.getId())).containsExactly("TransferInitiated");
    }

    /** Reserved, then the deadline of its open SCREEN step moved {@code offsetSeconds} from now. */
    private Transfer screeningTransferWithDeadline(UUID reservationId, long offsetSeconds) {
        fakeLedgerPort.willReserve(new ReservationResult.Ok(reservationId));
        Transfer transfer = initiate(UUID.randomUUID());
        sagaService.advance(transfer.getId());
        moveLatestScreenDeadline(transfer.getId(), offsetSeconds);
        return transfer;
    }

    private Transfer blockedTransferWithDeadline(UUID reservationId, long offsetSeconds) {
        Transfer transfer = screeningTransferWithDeadline(reservationId, 3600);
        sagaService.applyFraudResult(flagged(transfer.getId(), UUID.randomUUID()));
        moveLatestScreenDeadline(transfer.getId(), offsetSeconds);
        return transfer;
    }

    private void moveLatestScreenDeadline(UUID transferId, long offsetSeconds) {
        jdbcTemplate.update("""
                update saga_steps set deadline = now() + make_interval(secs => ?)
                where id = (select max(id) from saga_steps where transfer_id = ? and step = 'SCREEN')
                """, offsetSeconds, transferId);
    }

    private Transfer reload(Transfer transfer) {
        return transferRepository.findById(transfer.getId()).orElseThrow();
    }

    @Test
    @Tag("M3-S30")
    @DisplayName("A transfer fraud never answers fails and its reservation is released")
    void screeningTransferPastItsDeadlineFailsAndItsReservationIsReleased() {
        UUID reservationId = UUID.randomUUID();
        Transfer transfer = screeningTransferWithDeadline(reservationId, -1);

        int ended = sagaService.sweepOverdueTransfers();

        Transfer after = reload(transfer);
        assertThat(ended).isEqualTo(1);
        assertThat(after.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(after.getFailureReason()).isEqualTo("SCREENING_TIMEOUT");
        assertThat(eventTypesFor(transfer.getId()))
                .containsExactly("TransferInitiated", "FundsReserved", "TransferFailed");
        assertThat(failedEventReasonFor(transfer.getId())).isEqualTo("SCREENING_TIMEOUT");
        assertThat(fakeLedgerPort.releaseCalls()).containsExactly(reservationId);
        assertThat(stepsFor(transfer.getId()))
                .containsExactly("RESERVE OK", "SCREEN RETRY", "RELEASE RETRY", "RELEASE COMPENSATED");
        assertThat(fakeLedgerPort.postTransferCallCount()).isZero();
    }

    @Test
    @Tag("M3-S31")
    @DisplayName("A blocked transfer left past its window fails and its reservation is released")
    void blockedTransferPastItsWindowFailsAndItsReservationIsReleased() {
        UUID reservationId = UUID.randomUUID();
        Transfer transfer = blockedTransferWithDeadline(reservationId, -1);

        int ended = sagaService.sweepOverdueTransfers();

        Transfer after = reload(transfer);
        assertThat(ended).isEqualTo(1);
        assertThat(after.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(after.getFailureReason()).isEqualTo("VERIFICATION_EXPIRED");
        assertThat(eventTypesFor(transfer.getId()))
                .containsExactly("TransferInitiated", "FundsReserved", "TransferFailed");
        assertThat(failedEventReasonFor(transfer.getId())).isEqualTo("VERIFICATION_EXPIRED");
        assertThat(fakeLedgerPort.releaseCalls()).containsExactly(reservationId);
        assertThat(stepsFor(transfer.getId()))
                .containsExactly("RESERVE OK", "SCREEN RETRY", "SCREEN RETRY", "RELEASE RETRY", "RELEASE COMPENSATED");
    }

    @Test
    @Tag("M3-S32")
    @DisplayName("Transfers before their deadline are left alone")
    void transfersBeforeTheirDeadlineAreLeftAlone() {
        Transfer screening = screeningTransferWithDeadline(UUID.randomUUID(), 3600);
        Transfer blocked = blockedTransferWithDeadline(UUID.randomUUID(), 3600);

        int ended = sagaService.sweepOverdueTransfers();

        assertThat(ended).isZero();
        assertThat(reload(screening).getStatus()).isEqualTo(TransferStatus.SCREENING);
        assertThat(reload(blocked).getStatus()).isEqualTo(TransferStatus.BLOCKED);
        assertThat(stepsFor(screening.getId())).containsExactly("RESERVE OK", "SCREEN RETRY");
        assertThat(stepsFor(blocked.getId())).containsExactly("RESERVE OK", "SCREEN RETRY", "SCREEN RETRY");
        assertThat(eventTypesFor(screening.getId())).doesNotContain("TransferFailed");
        assertThat(eventTypesFor(blocked.getId())).doesNotContain("TransferFailed");
        assertThat(fakeLedgerPort.releaseCalls()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = FraudDecision.class,
            names = {"CLEARED", "FLAGGED"})
    @Tag("M3-S33")
    @DisplayName("A screening result arriving after the timeout is ignored")
    void screeningResultAfterTheTimeoutIsRecordedAndIgnored(FraudDecision decision) {
        Transfer transfer = screeningTransferWithDeadline(UUID.randomUUID(), -1);
        sagaService.sweepOverdueTransfers();
        UUID eventId = UUID.randomUUID();
        FraudResult late = decision == FraudDecision.CLEARED
                ? cleared(transfer.getId(), eventId)
                : flagged(transfer.getId(), eventId);

        sagaService.applyFraudResult(late);

        Transfer after = reload(transfer);
        assertThat(after.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(after.getFailureReason()).isEqualTo("SCREENING_TIMEOUT");
        assertThat(after.getFraudScore()).isNull();
        assertThat(processedEventCount(eventId)).isEqualTo(1);
        assertThat(fakeLedgerPort.postTransferCallCount()).isZero();
        assertThat(stepsFor(transfer.getId()))
                .containsExactly("RESERVE OK", "SCREEN RETRY", "RELEASE RETRY", "RELEASE COMPENSATED");
        assertThat(eventTypesFor(transfer.getId())).containsOnlyOnce("TransferFailed");
    }

    @Test
    void sweepStopsAtTheFirstUnavailableLedgerCallAndTheNextSweepEndsTheRest() {
        Transfer first = screeningTransferWithDeadline(UUID.randomUUID(), -2);
        Transfer second = screeningTransferWithDeadline(UUID.randomUUID(), -1);
        fakeLedgerPort.willFailRelease(new LedgerUnavailableException(new RuntimeException("deadline exceeded")));

        int ended = sagaService.sweepOverdueTransfers();

        assertThat(ended).isEqualTo(1);
        assertThat(fakeLedgerPort.releaseCalls()).hasSize(1);
        assertThat(reload(first).getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(stepsFor(first.getId())).last().isEqualTo("RELEASE RETRY");
        assertThat(reload(second).getStatus()).isEqualTo(TransferStatus.SCREENING);

        fakeLedgerPort.reset(); // ledger back
        int endedNext = sagaService.sweepOverdueTransfers();

        assertThat(endedNext).isEqualTo(1);
        assertThat(reload(first).getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(reload(second).getStatus()).isEqualTo(TransferStatus.FAILED);
    }

    @Test
    void releaseRefusedAfterATimeoutEndsTheReleaseStepWithTheLedgersAnswer() {
        fakeLedgerPort.willFailRelease(new LedgerRejectedException("NOT_FOUND", "reservation expired", null));
        Transfer transfer = screeningTransferWithDeadline(UUID.randomUUID(), -1);

        sagaService.sweepOverdueTransfers();

        assertThat(stepsFor(transfer.getId()))
                .containsExactly("RESERVE OK", "SCREEN RETRY", "RELEASE RETRY", "RELEASE FAILED");
        assertThat(reload(transfer).getStatus()).isEqualTo(TransferStatus.FAILED);
    }

    @Test
    void timingOutATransferAnotherActorMovedOnChangesNothing() {
        Transfer transfer = screeningTransferWithDeadline(UUID.randomUUID(), -1);
        sagaService.applyFraudResult(cleared(transfer.getId(), UUID.randomUUID())); // POSTED

        boolean applied =
                writer.applyTimedOut(transfer.getId(), transfer, TransferStatus.SCREENING, "SCREENING_TIMEOUT");

        assertThat(applied).isFalse();
        assertThat(reload(transfer).getStatus()).isEqualTo(TransferStatus.POSTED);
        assertThat(eventTypesFor(transfer.getId())).doesNotContain("TransferFailed");
        assertThat(stepsFor(transfer.getId())).doesNotContain("RELEASE RETRY");
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
