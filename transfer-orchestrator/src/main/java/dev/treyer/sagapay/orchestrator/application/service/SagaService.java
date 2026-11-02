package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.application.port.in.AdvanceSagaUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ApplyFraudResultUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ConfirmTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.GetTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.InitiateTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ListTransfersUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.PostClearedTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.SweepOverdueTransfersUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.SweepReprisePendingTransfersUseCase;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.application.port.out.OutboxPort;
import dev.treyer.sagapay.orchestrator.application.port.out.SagaStepPort;
import dev.treyer.sagapay.orchestrator.application.port.out.TransferPort;
import dev.treyer.sagapay.orchestrator.domain.FraudDecision;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import dev.treyer.sagapay.orchestrator.domain.InvalidVerificationCodeException;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferCursor;
import dev.treyer.sagapay.orchestrator.domain.TransferNotBlockedException;
import dev.treyer.sagapay.orchestrator.domain.TransferNotFoundException;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import dev.treyer.sagapay.orchestrator.domain.VerificationExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/** Only {@link #initiateTransfer} is {@code @Transactional}: the other steps
 * call the ledger outside any transaction, then hand their writes to {@link
 * SagaTransitionWriter}, so no database transaction stays open across a network
 * call. */
@Service
public class SagaService
        implements InitiateTransferUseCase,
                AdvanceSagaUseCase,
                ApplyFraudResultUseCase,
                PostClearedTransferUseCase,
                SweepReprisePendingTransfersUseCase,
                SweepOverdueTransfersUseCase,
                ListTransfersUseCase,
                GetTransferUseCase,
                ConfirmTransferUseCase {

    private static final Logger log = LoggerFactory.getLogger(SagaService.class);

    /** Per waiting status and sweep: a long outage leaves a backlog the next sweeps take in turn. */
    private static final int OVERDUE_BATCH_SIZE = 100;

    private record Deadline(TransferStatus waitingStatus, String failureReason) {}

    private static final List<Deadline> DEADLINES = List.of(
            new Deadline(TransferStatus.SCREENING, "SCREENING_TIMEOUT"),
            new Deadline(TransferStatus.BLOCKED, "VERIFICATION_EXPIRED"));

    private final TransferPort transfers;
    private final SagaStepPort sagaSteps;
    private final OutboxPort outbox;
    private final LedgerPort ledger;
    private final JsonMapper jsonMapper;
    private final SagaTransitionWriter writer;
    private final long repriseGracePeriodMs;
    private final byte[] verificationCode;

    public SagaService(
            TransferPort transfers,
            SagaStepPort sagaSteps,
            OutboxPort outbox,
            LedgerPort ledger,
            JsonMapper jsonMapper,
            SagaTransitionWriter writer,
            @Value("${saga.reprise.grace-period-ms:5000}") long repriseGracePeriodMs,
            @Value("${sagapay.fraud.verification-code}") String verificationCode) {
        if (verificationCode.isBlank()) {
            throw new IllegalArgumentException("sagapay.fraud.verification-code must not be blank");
        }
        this.transfers = transfers;
        this.sagaSteps = sagaSteps;
        this.outbox = outbox;
        this.ledger = ledger;
        this.jsonMapper = jsonMapper;
        this.writer = writer;
        this.repriseGracePeriodMs = repriseGracePeriodMs;
        this.verificationCode = verificationCode.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    @Transactional
    public Result initiateTransfer(
            UUID senderId,
            UUID senderAccountId,
            UUID recipientId,
            UUID recipientAccountId,
            Money amount,
            UUID idempotencyKey,
            String note) {
        UUID id = UUID.randomUUID();
        int inserted = transfers.insertIfAbsent(
                id,
                idempotencyKey,
                senderId,
                senderAccountId,
                recipientId,
                recipientAccountId,
                amount.amount(),
                amount.currency().getCurrencyCode(),
                note);

        Transfer transfer = transfers
                .findBySenderIdAndIdempotencyKey(senderId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "transfer row vanished for sender " + senderId + " / idempotency key " + idempotencyKey));

        if (inserted == 1) {
            outbox.save(OutboxEvents.forTransfer(jsonMapper, transfer, "TransferInitiated"));
        }
        return new Result(transfer, inserted == 1);
    }

    @Override
    public void advance(UUID transferId) {
        Transfer transfer = requireTransfer(transferId);
        if (transfer.getStatus() != TransferStatus.INITIATED) {
            // The eager path and the reprise poller may race on a transfer.
            return;
        }

        ReservationResult result;
        try {
            result = ledger.checkAndReserve(
                    transferId.toString(),
                    transfer.getSenderAccountId(),
                    Money.of(transfer.getAmount(), transfer.getCurrency()));
        } catch (LedgerRejectedException e) {
            writer.applyRejected(transferId, transfer, TransferStatus.INITIATED, "RESERVE", "RESERVE_REJECTED", e);
            return;
        }

        switch (result) {
            case ReservationResult.Ok ok -> writer.applyReserved(transferId, transfer, ok.reservationId());
            case ReservationResult.InsufficientFunds ignored -> writer.applyInsufficientFunds(transferId, transfer);
        }
    }

    @Override
    public void applyFraudResult(FraudResult result) {
        if (result.decision() == FraudDecision.FLAGGED) {
            if (writer.applyFlagged(result)) {
                log.info("transfer blocked on fraud flag", kv("transfer.id", result.transferId()));
            } else {
                log.info("flagged fraud result skipped", kv("transfer.id", result.transferId()));
            }
            return;
        }
        if (!writer.applyCleared(result)) {
            return;
        }
        try {
            postCleared(result.transferId());
        } catch (LedgerUnavailableException e) {
            // CLEARED is durable: the reprise poller posts it once the ledger is back.
            log.warn(
                    "ledger unavailable, transfer stays cleared for the reprise poller",
                    kv("transfer.id", result.transferId()),
                    e);
        }
    }

    @Override
    public void postCleared(UUID transferId) {
        Transfer transfer = requireTransfer(transferId);
        if (transfer.getStatus() != TransferStatus.CLEARED) {
            return;
        }

        boolean posted;
        try {
            posted = ledger.postTransfer(
                    transferId.toString(),
                    transfer.getSenderAccountId(),
                    transfer.getRecipientAccountId(),
                    Money.of(transfer.getAmount(), transfer.getCurrency()));
        } catch (LedgerRejectedException e) {
            // Typically an expired reservation: the ledger will never post it, and
            // a refused postTransfer moved no money, so FAILED is the outcome.
            if (writer.applyRejectedAndRelease(
                    transferId, transfer, TransferStatus.CLEARED, "POST", "POST_REJECTED", e)) {
                releaseReservation(transfer);
            }
            return;
        }
        if (!posted) {
            // Not expected once cleared: left CLEARED for the poller to retry.
            return;
        }

        writer.applyPosted(transferId, transfer);
    }

    @Override
    public int sweepStuckTransfers() {
        Instant cutoff = Instant.now().minusMillis(repriseGracePeriodMs);
        int resumed = 0;
        try {
            for (Transfer transfer : transfers.findByStatusAndUpdatedAtBefore(TransferStatus.INITIATED, cutoff)) {
                advance(transfer.getId());
                resumed++;
            }
            for (Transfer transfer : transfers.findByStatusAndUpdatedAtBefore(TransferStatus.CLEARED, cutoff)) {
                postCleared(transfer.getId());
                resumed++;
            }
            for (Transfer transfer : transfers.findFailedWithPendingRelease(cutoff)) {
                releaseReservation(transfer);
                resumed++;
            }
        } catch (LedgerUnavailableException e) {
            // The remaining transfers need the same ledger: retry them next sweep.
            log.warn("ledger unavailable, saga reprise sweep stopped early", kv("transfer.resumed.count", resumed), e);
        }
        return resumed;
    }

    @Override
    public int sweepOverdueTransfers() {
        Instant now = Instant.now();
        int ended = 0;
        try {
            for (Deadline deadline : DEADLINES) {
                for (Transfer transfer : transfers.findOverdue(deadline.waitingStatus(), now, OVERDUE_BATCH_SIZE)) {
                    try {
                        if (!writer.applyTimedOut(
                                transfer.getId(), transfer, deadline.waitingStatus(), deadline.failureReason())) {
                            continue;
                        }
                        ended++;
                        log.info(
                                "overdue transfer failed",
                                kv("transfer.id", transfer.getId()),
                                kv("transfer.failure_reason", deadline.failureReason()));
                        releaseReservation(transfer);
                    } catch (LedgerUnavailableException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        // One bad transfer must not keep the others' reservations held.
                        log.error("overdue transfer could not be failed", kv("transfer.id", transfer.getId()), e);
                    }
                }
            }
        } catch (LedgerUnavailableException e) {
            // The pending release is left to the reprise poller; the remaining transfers
            // need the same ledger and stay overdue for the next sweep.
            log.warn("ledger unavailable, overdue sweep stopped early", kv("transfer.failed.count", ended), e);
        }
        return ended;
    }

    /** A refusal ends the step; an unavailability propagates and leaves it
     * pending for the reprise poller. */
    private void releaseReservation(Transfer transfer) {
        UUID transferId = transfer.getId();
        try {
            ledger.releaseReservation(transferId.toString(), transfer.getReservationId());
        } catch (LedgerRejectedException e) {
            writer.recordReleaseRefused(transferId, e);
            return;
        }
        writer.recordReleased(transferId);
    }

    @Override
    public Page listTransfers(
            UUID userId, Direction direction, TransferStatus status, TransferCursor after, int limit) {
        boolean includeSent = direction != Direction.RECEIVED;
        boolean includeReceived = direction != Direction.SENT;
        Instant afterCreatedAt = after == null ? null : after.createdAt();
        UUID afterId = after == null ? null : after.id();

        // limit + 1: the extra row only signals a next page.
        List<Transfer> rows = transfers.findPageForUser(
                userId, includeSent, includeReceived, status, afterCreatedAt, afterId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<Transfer> items = hasMore ? rows.subList(0, limit) : rows;
        TransferCursor next = hasMore ? TransferCursor.of(items.get(items.size() - 1)) : null;
        return new Page(items, next);
    }

    @Override
    public TransferWithSteps getTransfer(UUID transferId) {
        Transfer transfer = transfers.findById(transferId).orElseThrow(() -> new TransferNotFoundException(transferId));
        List<SagaStep> steps = sagaSteps.findByTransferId(transferId);
        return new TransferWithSteps(transfer, steps, screeningVerdict(transfer, steps));
    }

    /** The decision is read from the first SCREEN step that carries one: the
     * transfer keeps the score and reasons, not the decision, and a later
     * confirmation changes its status but not what fraud decided. */
    private ScreeningVerdict screeningVerdict(Transfer transfer, List<SagaStep> steps) {
        if (transfer.getFraudScore() == null) {
            return null;
        }
        FraudDecision decision = steps.stream()
                .filter(step -> step.getStep().equals(SagaTransitionWriter.SCREEN_STEP) && step.getDetail() != null)
                .map(step -> jsonMapper.readTree(step.getDetail()).get("decision"))
                .filter(node -> node != null)
                .map(node -> FraudDecision.valueOf(node.asString()))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("screened transfer " + transfer.getId() + " has no verdict step"));
        return new ScreeningVerdict(decision, transfer.getFraudScore(), transfer.getFraudReasons());
    }

    @Override
    public Transfer confirmTransfer(UUID transferId, String code) {
        Transfer transfer = transfers.findById(transferId).orElseThrow(() -> new TransferNotFoundException(transferId));
        if (isExpired(transfer)) {
            throw new VerificationExpiredException(transferId);
        }
        if (transfer.getStatus() != TransferStatus.BLOCKED) {
            throw new TransferNotBlockedException(transferId);
        }
        if (!MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8), verificationCode)) {
            log.warn("verification code rejected", kv("transfer.id", transferId));
            throw new InvalidVerificationCodeException(transferId);
        }
        if (!writer.applyConfirmed(transferId, Instant.now())) {
            throw lostConfirmation(transferId);
        }
        log.info("transfer confirmed by sender", kv("transfer.id", transferId));
        return requireTransfer(transferId);
    }

    /** The compare-and-set found no BLOCKED transfer inside its window: the expiry job or another
     * confirmation won. Still BLOCKED means the deadline passed after the read. */
    private RuntimeException lostConfirmation(UUID transferId) {
        Transfer current = requireTransfer(transferId);
        if (failedAsExpired(current) || current.getStatus() == TransferStatus.BLOCKED) {
            return new VerificationExpiredException(transferId);
        }
        return new TransferNotBlockedException(transferId);
    }

    private static boolean failedAsExpired(Transfer transfer) {
        return transfer.getStatus() == TransferStatus.FAILED
                && "VERIFICATION_EXPIRED".equals(transfer.getFailureReason());
    }

    private boolean isExpired(Transfer transfer) {
        if (failedAsExpired(transfer)) {
            return true;
        }
        if (transfer.getStatus() != TransferStatus.BLOCKED) {
            return false;
        }
        Instant deadline = sagaSteps.findByTransferId(transfer.getId()).stream()
                .filter(step -> step.getStep().equals(SagaTransitionWriter.SCREEN_STEP)
                        && step.getOutcome().equals("RETRY"))
                .reduce((first, second) -> second)
                .map(SagaStep::getDeadline)
                .orElseThrow(() -> new IllegalStateException(
                        "blocked transfer " + transfer.getId() + " has no open confirmation window"));
        return deadline.isBefore(Instant.now());
    }

    private Transfer requireTransfer(UUID transferId) {
        return transfers
                .findById(transferId)
                .orElseThrow(() -> new IllegalArgumentException("unknown transfer " + transferId));
    }
}
