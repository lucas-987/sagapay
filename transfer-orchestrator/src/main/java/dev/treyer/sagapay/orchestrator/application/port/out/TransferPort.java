package dev.treyer.sagapay.orchestrator.application.port.out;

import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferPort {

    /** @return 0 when a transfer with this sender and idempotency key already exists. */
    int insertIfAbsent(UUID id, UUID idempotencyKey, UUID senderId, UUID senderAccountId, UUID recipientId,
                       UUID recipientAccountId, BigDecimal amount, String currency, String note);

    Optional<Transfer> findById(UUID id);

    Optional<Transfer> findBySenderIdAndIdempotencyKey(UUID senderId, UUID idempotencyKey);

    List<Transfer> findByStatusAndUpdatedAtBefore(TransferStatus status, Instant cutoff);

    /** Newest first, strictly after {@code (afterCreatedAt, afterId)} when given. */
    List<Transfer> findPageForUser(UUID userId, boolean includeSent, boolean includeReceived, TransferStatus status,
                                   Instant afterCreatedAt, UUID afterId, int maxRows);

    /** The transition methods return 0 when the transfer is not in {@code fromStatus}. */
    int transitionWithReservation(UUID id, TransferStatus fromStatus, TransferStatus toStatus, UUID reservationId);

    int transitionStatus(UUID id, TransferStatus fromStatus, TransferStatus toStatus);

    int transitionToFailed(UUID id, TransferStatus fromStatus, TransferStatus toStatus, String failureReason);
}
