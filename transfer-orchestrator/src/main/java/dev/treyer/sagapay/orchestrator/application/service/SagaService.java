package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.OutboxRepository;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.SagaStepRepository;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.TransferRepository;
import dev.treyer.sagapay.orchestrator.application.port.in.AdvanceSagaUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ConfirmTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ContinueReservedTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.GetTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.InitiateTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ListTransfersUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.SweepReprisePendingTransfersUseCase;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
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
import org.springframework.data.domain.PageRequest;
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

    private final TransferRepository transfers;
    private final SagaStepRepository sagaSteps;
    private final OutboxRepository outbox;
    private final LedgerPort ledger;
    private final JsonMapper jsonMapper;
    private final SagaTransitionWriter writer;
    private final long repriseGracePeriodMs;

    public SagaService(TransferRepository transfers, SagaStepRepository sagaSteps, OutboxRepository outbox,
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
            // Already advanced by a concurrent caller (the eager path and the
            // reprise poller racing on the same transfer) -- idempotent no-op,
            // not an error.
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
                    // No fraud branch in M2 -- reserved funds chain straight into
                    // posting, in the same call rather than waiting for a
                    // separate trigger.
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
            // Not reserved (yet), or already posted by a concurrent caller --
            // idempotent no-op. Lets the reprise poller call this blindly for
            // any transfer it finds stuck in RESERVED.
            return;
        }

        boolean posted;
        try {
            posted = ledger.postTransfer(transferId.toString(), transfer.getSenderAccountId(),
                    transfer.getRecipientAccountId(), Money.of(transfer.getAmount(), transfer.getCurrency()));
        } catch (LedgerRejectedException e) {
            // Typically the reservation expired on the ledger side while this
            // transfer sat in RESERVED (crash, long outage): the ledger released
            // the hold and will never post against it. No money moved -- the
            // ledger rolls back the whole postTransfer on a rejection -- so
            // FAILED is the true outcome, not a guess.
            writer.applyRejected(transferId, transfer, TransferStatus.RESERVED, "POST", "POST_REJECTED", e);
            return;
        }
        if (!posted) {
            // The ledger never reports posted=false for a transfer it already
            // accepted a reservation for (see LedgerService.postTransfer) -- left
            // in RESERVED rather than guessed at, so the poller retries it.
            return;
        }

        writer.applyPosted(transferId, transfer);
    }

    @Override
    public int sweepStuckTransfers() {
        Instant cutoff = Instant.now().minusMillis(repriseGracePeriodMs);
        int resumed = 0;
        try {
            // INITIATED first: advance() chains into continueFromReserved()
            // itself, so a transfer resumed here is already past RESERVED by the
            // time RESERVED is queried below.
            for (Transfer transfer : transfers.findByStatusAndUpdatedAtBefore(TransferStatus.INITIATED, cutoff)) {
                advance(transfer.getId());
                resumed++;
            }
            for (Transfer transfer : transfers.findByStatusAndUpdatedAtBefore(TransferStatus.RESERVED, cutoff)) {
                continueFromReserved(transfer.getId());
                resumed++;
            }
        } catch (LedgerUnavailableException e) {
            // Every remaining transfer needs the same ledger: stop here rather
            // than hit it (or the open circuit) once per row. What's left keeps
            // its current state for the next sweep.
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

        // Request limit + 1: the extra row (if present) only tells us whether a
        // next page exists, stripped before returning items -- same idiom as
        // the ledger's ListPostingsUseCase.
        List<Transfer> rows = transfers.findPageForUser(userId, includeSent, includeReceived, status,
                afterCreatedAt, afterId, PageRequest.ofSize(limit + 1));
        boolean hasMore = rows.size() > limit;
        List<Transfer> items = hasMore ? rows.subList(0, limit) : rows;
        TransferCursor next = hasMore ? TransferCursor.of(items.get(items.size() - 1)) : null;
        return new Page(items, next);
    }

    @Override
    public TransferWithSteps getTransfer(UUID transferId) {
        Transfer transfer = transfers.findById(transferId).orElseThrow(() -> new TransferNotFoundException(transferId));
        return new TransferWithSteps(transfer, sagaSteps.findByTransferIdOrderByAtAsc(transferId));
    }

    @Override
    public void confirmTransfer(UUID transferId, String verificationToken) {
        // 404 first if the id itself doesn't exist; otherwise always
        // TransferNotBlockedException -- BLOCKED isn't even a value TransferStatus
        // can hold in M2 (no fraud branch), so there's nothing else to check.
        transfers.findById(transferId).orElseThrow(() -> new TransferNotFoundException(transferId));
        throw new TransferNotBlockedException(transferId);
    }

    private Transfer requireTransfer(UUID transferId) {
        return transfers.findById(transferId)
                .orElseThrow(() -> new IllegalArgumentException("unknown transfer " + transferId));
    }
}
