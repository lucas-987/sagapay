package dev.treyer.sagapay.orchestrator.application.port.out;

import dev.treyer.sagapay.orchestrator.domain.FailureReason;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferPort {

    /** @return 0 when a transfer with this sender and idempotency key already exists. */
    int insertIfAbsent(
            UUID id,
            UUID idempotencyKey,
            UUID senderId,
            UUID senderAccountId,
            UUID recipientId,
            UUID recipientAccountId,
            BigDecimal amount,
            String currency,
            String note);

    Optional<Transfer> findById(UUID id);

    Optional<Transfer> findBySenderIdAndIdempotencyKey(UUID senderId, UUID idempotencyKey);

    List<Transfer> findByStatusAndUpdatedAtBefore(TransferStatus status, Instant cutoff);

    /** FAILED transfers whose latest RELEASE step is RETRY and which have been
     * FAILED since before {@code cutoff}. */
    List<Transfer> findFailedWithPendingRelease(Instant cutoff);

    /** At most {@code limit} transfers in {@code status} whose latest open SCREEN step has a
     * deadline before {@code now}, the oldest deadline first, then by id. */
    List<Transfer> findOverdue(TransferStatus status, Instant now, int limit);

    /** Newest first, strictly after {@code (afterCreatedAt, afterId)} when given. */
    List<Transfer> findPageForUser(
            UUID userId,
            boolean includeSent,
            boolean includeReceived,
            TransferStatus status,
            Instant afterCreatedAt,
            UUID afterId,
            int maxRows);

    /** The transition methods return 0 when the transfer is not in {@code fromStatus}. */
    int transitionWithReservation(UUID id, TransferStatus fromStatus, TransferStatus toStatus, UUID reservationId);

    int transitionStatus(UUID id, TransferStatus fromStatus, TransferStatus toStatus);

    int transitionToCleared(UUID id, BigDecimal fraudScore, List<String> fraudReasons);

    int transitionToBlocked(UUID id, BigDecimal fraudScore, List<String> fraudReasons);

    /** Only while the confirmation deadline is not past at {@code now}. */
    int transitionBlockedToCleared(UUID id, Instant now);

    int transitionToFailed(UUID id, TransferStatus fromStatus, TransferStatus toStatus, FailureReason failureReason);
}
