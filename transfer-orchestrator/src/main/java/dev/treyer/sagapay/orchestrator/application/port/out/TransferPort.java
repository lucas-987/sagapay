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

    /** At most {@code limit}, the longest in {@code status} first, then by id. */
    List<Transfer> findByStatusAndUpdatedAtBefore(TransferStatus status, Instant cutoff, int limit);

    /** FAILED transfers whose reservation is still to release, FAILED since before
     * {@code cutoff}: at most {@code limit}, in the same order. */
    List<Transfer> findFailedWithPendingRelease(Instant cutoff, int limit);

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
    int transitionWithReservation(
            UUID id,
            TransferStatus fromStatus,
            TransferStatus toStatus,
            UUID reservationId,
            Instant reservationExpiresAt);

    int transitionStatus(UUID id, TransferStatus fromStatus, TransferStatus toStatus);

    /** The three screening outcomes return 0 as well once the screening deadline is past at {@code now}. */
    int transitionToCleared(UUID id, BigDecimal fraudScore, List<String> fraudReasons, Instant now);

    int transitionToBlocked(UUID id, BigDecimal fraudScore, List<String> fraudReasons, Instant now);

    /** A flagged transfer whose reservation leaves the sender no time to confirm. */
    int transitionFlaggedToExpired(UUID id, BigDecimal fraudScore, List<String> fraudReasons, Instant now);

    /** Only while the confirmation deadline is not past at {@code now}. */
    int transitionBlockedToCleared(UUID id, Instant now);

    int transitionToFailed(UUID id, TransferStatus fromStatus, TransferStatus toStatus, FailureReason failureReason);

    void setReleasePending(UUID id, boolean pending);
}
