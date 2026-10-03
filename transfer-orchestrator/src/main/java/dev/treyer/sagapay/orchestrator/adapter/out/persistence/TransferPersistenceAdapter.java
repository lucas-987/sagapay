package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.application.port.out.TransferPort;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class TransferPersistenceAdapter implements TransferPort {

    private final TransferRepository repository;

    TransferPersistenceAdapter(TransferRepository repository) {
        this.repository = repository;
    }

    @Override
    public int insertIfAbsent(
            UUID id,
            UUID idempotencyKey,
            UUID senderId,
            UUID senderAccountId,
            UUID recipientId,
            UUID recipientAccountId,
            BigDecimal amount,
            String currency,
            String note) {
        return repository.insertIfAbsent(
                id, idempotencyKey, senderId, senderAccountId, recipientId, recipientAccountId, amount, currency, note);
    }

    @Override
    public Optional<Transfer> findById(UUID id) {
        return repository.findById(id);
    }

    @Override
    public Optional<Transfer> findBySenderIdAndIdempotencyKey(UUID senderId, UUID idempotencyKey) {
        return repository.findBySenderIdAndIdempotencyKey(senderId, idempotencyKey);
    }

    @Override
    public List<Transfer> findByStatusAndUpdatedAtBefore(TransferStatus status, Instant cutoff) {
        return repository.findByStatusAndUpdatedAtBefore(status, cutoff);
    }

    @Override
    public List<Transfer> findPageForUser(
            UUID userId,
            boolean includeSent,
            boolean includeReceived,
            TransferStatus status,
            Instant afterCreatedAt,
            UUID afterId,
            int maxRows) {
        return repository.findPageForUser(
                userId, includeSent, includeReceived, status, afterCreatedAt, afterId, PageRequest.ofSize(maxRows));
    }

    @Override
    public int transitionWithReservation(
            UUID id, TransferStatus fromStatus, TransferStatus toStatus, UUID reservationId) {
        return repository.transitionWithReservation(id, fromStatus, toStatus, reservationId);
    }

    @Override
    public int transitionStatus(UUID id, TransferStatus fromStatus, TransferStatus toStatus) {
        return repository.transitionStatus(id, fromStatus, toStatus);
    }

    @Override
    public int transitionToFailed(UUID id, TransferStatus fromStatus, TransferStatus toStatus, String failureReason) {
        return repository.transitionToFailed(id, fromStatus, toStatus, failureReason);
    }
}
