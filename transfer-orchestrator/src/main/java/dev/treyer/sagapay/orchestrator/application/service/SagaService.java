package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.application.port.in.AdvanceSagaUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ConfirmTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ContinueReservedTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.GetTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.InitiateTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ListTransfersUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.SweepReprisePendingTransfersUseCase;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.application.port.out.OutboxPort;
import dev.treyer.sagapay.orchestrator.application.port.out.SagaStepPort;
import dev.treyer.sagapay.orchestrator.application.port.out.TransferPort;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferCursor;
import dev.treyer.sagapay.orchestrator.domain.TransferNotBlockedException;
import dev.treyer.sagapay.orchestrator.domain.TransferNotFoundException;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Only {@link #initiateTransfer} is {@code @Transactional}: the other steps
 * call the ledger outside any transaction, then hand their writes to {@link
 * SagaTransitionWriter}, so no database transaction stays open across a network
 * call. */
@Service
public class SagaService implements InitiateTransferUseCase, AdvanceSagaUseCase, ContinueReservedTransferUseCase,
        SweepReprisePendingTransfersUseCase, ListTransfersUseCase, GetTransferUseCase, ConfirmTransferUseCase {

    private static final Logger log = LoggerFactory.getLogger(SagaService.class);

    private final TransferPort transfers;
    private final SagaStepPort sagaSteps;
    private final OutboxPort outbox;
    private final LedgerPort ledger;
    private final JsonMapper jsonMapper;
    private final SagaTransitionWriter writer;
    private final long repriseGracePeriodMs;

    public SagaService(TransferPort transfers, SagaStepPort sagaSteps, OutboxPort outbox,
                        LedgerPort ledger, JsonMapper jsonMapper, SagaTransitionWriter writer,
                        @Value("${saga.reprise.grace-period-ms:5000}") long repriseGracePeriodMs) {
        this.transfers = transfers;
        this.sagaSteps = sagaSteps;
        this.outbox = outbox;
        this.ledger = ledger;
        this.jsonMapper = jsonMapper;
        this.writer = writer;
        this.repriseGracePeriodMs = repriseGracePeriodMs;
    }

    @Override
    @Transactional
    public Result initiateTransfer(UUID senderId, UUID senderAccountId, UUID recipientId, UUID recipientAccountId,
                                    Money amount, UUID idempotencyKey, String note) {
        UUID id = UUID.randomUUID();
        int inserted = transfers.insertIfAbsent(id, idempotencyKey, senderId, senderAccountId, recipientId,
                recipientAccountId, amount.amount(), amount.currency().getCurrencyCode(), note);

        Transfer transfer = transfers.findBySenderIdAndIdempotencyKey(senderId, idempotencyKey)
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
            result = ledger.checkAndReserve(transferId.toString(), transfer.getSenderAccountId(),
                    Money.of(transfer.getAmount(), transfer.getCurrency()));
        } catch (LedgerRejectedException e) {
            writer.applyRejected(transferId, transfer, TransferStatus.INITIATED, "RESERVE", "RESERVE_REJECTED", e);
            return;
        }

        switch (result) {
            case ReservationResult.Ok ok -> {
                boolean applied = writer.applyReserved(transferId, transfer, ok.reservationId());
                if (applied) {
                    // No fraud screening yet: reserved funds are posted at once.
                    continueFromReserved(transferId);
                }
            }
            case ReservationResult.InsufficientFunds ignored -> writer.applyInsufficientFunds(transferId, transfer);
        }
    }

    @Override
    public void continueFromReserved(UUID transferId) {
        Transfer transfer = requireTransfer(transferId);
        if (transfer.getStatus() != TransferStatus.RESERVED) {
            return;
        }

        boolean posted;
        try {
            posted = ledger.postTransfer(transferId.toString(), transfer.getSenderAccountId(),
                    transfer.getRecipientAccountId(), Money.of(transfer.getAmount(), transfer.getCurrency()));
        } catch (LedgerRejectedException e) {
            // Typically an expired reservation: the ledger will never post it, and
            // a refused postTransfer moved no money, so FAILED is the outcome.
            writer.applyRejected(transferId, transfer, TransferStatus.RESERVED, "POST", "POST_REJECTED", e);
            return;
        }
        if (!posted) {
            // Not expected once reserved: left RESERVED for the poller to retry.
            return;
        }

        writer.applyPosted(transferId, transfer);
    }

    @Override
    public int sweepStuckTransfers() {
        Instant cutoff = Instant.now().minusMillis(repriseGracePeriodMs);
        int resumed = 0;
        try {
            // INITIATED first: advance() goes on to post, so those transfers are
            // done before RESERVED is queried.
            for (Transfer transfer : transfers.findByStatusAndUpdatedAtBefore(TransferStatus.INITIATED, cutoff)) {
                advance(transfer.getId());
                resumed++;
            }
            for (Transfer transfer : transfers.findByStatusAndUpdatedAtBefore(TransferStatus.RESERVED, cutoff)) {
                continueFromReserved(transfer.getId());
                resumed++;
            }
        } catch (LedgerUnavailableException e) {
            // The remaining transfers need the same ledger: retry them next sweep.
            log.warn("Ledger unavailable, saga reprise sweep stopped early ({} resumed so far)", resumed, e);
        }
        return resumed;
    }

    @Override
    public Page listTransfers(UUID userId, Direction direction, TransferStatus status, TransferCursor after, int limit) {
        boolean includeSent = direction != Direction.RECEIVED;
        boolean includeReceived = direction != Direction.SENT;
        Instant afterCreatedAt = after == null ? null : after.createdAt();
        UUID afterId = after == null ? null : after.id();

        // limit + 1: the extra row only signals a next page.
        List<Transfer> rows = transfers.findPageForUser(userId, includeSent, includeReceived, status,
                afterCreatedAt, afterId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<Transfer> items = hasMore ? rows.subList(0, limit) : rows;
        TransferCursor next = hasMore ? TransferCursor.of(items.get(items.size() - 1)) : null;
        return new Page(items, next);
    }

    @Override
    public TransferWithSteps getTransfer(UUID transferId) {
        Transfer transfer = transfers.findById(transferId).orElseThrow(() -> new TransferNotFoundException(transferId));
        return new TransferWithSteps(transfer, sagaSteps.findByTransferId(transferId));
    }

    @Override
    public void confirmTransfer(UUID transferId, String verificationToken) {
        // No transfer can be blocked yet.
        transfers.findById(transferId).orElseThrow(() -> new TransferNotFoundException(transferId));
        throw new TransferNotBlockedException(transferId);
    }

    private Transfer requireTransfer(UUID transferId) {
        return transfers.findById(transferId)
                .orElseThrow(() -> new IllegalArgumentException("unknown transfer " + transferId));
    }
}
