package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.domain.FailureReason;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findBySenderIdAndIdempotencyKey(UUID senderId, UUID idempotencyKey);

    /** {@code updatedAt} tells how long a transfer has been in its status: only
     * transitions touch it. */
    List<Transfer> findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
            TransferStatus status, Instant cutoff, Pageable pageable);

    @Query("select t from Transfer t where t.releasePending = true and t.updatedAt < :cutoff "
            + "order by t.updatedAt, t.id")
    List<Transfer> findFailedWithPendingRelease(@Param("cutoff") Instant cutoff, Pageable pageable);

    /** The casts type the optional filters: a parameter used only in
     * {@code ? is null} gives Postgres no type. */
    @Query("select t from Transfer t where "
            + "((:includeSent = true and t.senderId = :userId) or (:includeReceived = true and t.recipientId = :userId)) "
            + "and (cast(:status as string) is null or t.status = :status) "
            + "and (cast(:afterCreatedAt as timestamp) is null or t.createdAt < :afterCreatedAt "
            + "     or (t.createdAt = :afterCreatedAt and t.id < :afterId)) "
            + "order by t.createdAt desc, t.id desc")
    List<Transfer> findPageForUser(
            @Param("userId") UUID userId,
            @Param("includeSent") boolean includeSent,
            @Param("includeReceived") boolean includeReceived,
            @Param("status") TransferStatus status,
            @Param("afterCreatedAt") Instant afterCreatedAt,
            @Param("afterId") UUID afterId,
            Pageable pageable);

    /** The latest open SCREEN step decides, by id: a blocked transfer keeps its
     * screening deadline step, then gets the confirmation window's. Oldest deadline
     * first, then by id, so the order is the same on every call. */
    @Query(nativeQuery = true, value = """
            select t.* from transfers t
            join lateral (select s.deadline from saga_steps s
                          where s.transfer_id = t.id and s.step = :screenStep and s.outcome = :openOutcome
                          order by s.id desc limit 1) d on true
            where t.status = :status
              and d.deadline < :now
            order by d.deadline, t.id
            limit :limit
            """)
    List<Transfer> findOverdue(
            @Param("status") String status,
            @Param("now") Instant now,
            @Param("limit") int limit,
            @Param("screenStep") String screenStep,
            @Param("openOutcome") String openOutcome);

    /** @return 0 when a replay already created the transfer. Unlike a caught
     * constraint violation, {@code ON CONFLICT DO NOTHING} leaves the transaction
     * usable for the read-back. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into transfers (id, idempotency_key, sender_id, sender_account_id, recipient_id,
                recipient_account_id, amount, currency, note, status, created_at, updated_at)
            values (:id, :idempotencyKey, :senderId, :senderAccountId, :recipientId, :recipientAccountId,
                :amount, :currency, :note, 'INITIATED', now(), now())
            on conflict (sender_id, idempotency_key) do nothing
            """)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("senderId") UUID senderId,
            @Param("senderAccountId") UUID senderAccountId,
            @Param("recipientId") UUID recipientId,
            @Param("recipientAccountId") UUID recipientAccountId,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("note") String note);

    @Modifying
    @Query("update Transfer t set t.status = :toStatus, t.reservationId = :reservationId, "
            + "t.reservationExpiresAt = :reservationExpiresAt, t.updatedAt = CURRENT_TIMESTAMP "
            + "where t.id = :id and t.status = :fromStatus")
    int transitionWithReservation(
            @Param("id") UUID id,
            @Param("fromStatus") TransferStatus fromStatus,
            @Param("toStatus") TransferStatus toStatus,
            @Param("reservationId") UUID reservationId,
            @Param("reservationExpiresAt") Instant reservationExpiresAt);

    @Modifying
    @Query("update Transfer t set t.status = :toStatus, t.updatedAt = CURRENT_TIMESTAMP "
            + "where t.id = :id and t.status = :fromStatus")
    int transitionStatus(
            @Param("id") UUID id,
            @Param("fromStatus") TransferStatus fromStatus,
            @Param("toStatus") TransferStatus toStatus);

    /** While a transfer is in screening, its only open SCREEN step is the screening deadline. */
    String BEFORE_SCREENING_DEADLINE = "t.status = 'SCREENING' and not exists (select 1 from SagaStep s "
            + "where s.transferId = t.id and s.step = 'SCREEN' and s.outcome = 'RETRY' and s.deadline < :now)";

    @Modifying
    @Query("update Transfer t set t.status = 'CLEARED', t.fraudDecision = 'CLEARED', t.fraudScore = :fraudScore, "
            + "t.fraudReasons = :fraudReasons, t.updatedAt = CURRENT_TIMESTAMP where t.id = :id and "
            + BEFORE_SCREENING_DEADLINE)
    int transitionToCleared(
            @Param("id") UUID id,
            @Param("fraudScore") BigDecimal fraudScore,
            @Param("fraudReasons") List<String> fraudReasons,
            @Param("now") Instant now);

    @Modifying
    @Query("update Transfer t set t.status = 'BLOCKED', t.fraudDecision = 'FLAGGED', t.fraudScore = :fraudScore, "
            + "t.fraudReasons = :fraudReasons, t.updatedAt = CURRENT_TIMESTAMP where t.id = :id and "
            + BEFORE_SCREENING_DEADLINE)
    int transitionToBlocked(
            @Param("id") UUID id,
            @Param("fraudScore") BigDecimal fraudScore,
            @Param("fraudReasons") List<String> fraudReasons,
            @Param("now") Instant now);

    @Modifying
    @Query("update Transfer t set t.status = 'FAILED', t.failureReason = 'VERIFICATION_EXPIRED', "
            + "t.fraudDecision = 'FLAGGED', t.fraudScore = :fraudScore, t.fraudReasons = :fraudReasons, "
            + "t.updatedAt = CURRENT_TIMESTAMP where t.id = :id and " + BEFORE_SCREENING_DEADLINE)
    int transitionFlaggedToExpired(
            @Param("id") UUID id,
            @Param("fraudScore") BigDecimal fraudScore,
            @Param("fraudReasons") List<String> fraudReasons,
            @Param("now") Instant now);

    @Modifying
    @Query(
            "update Transfer t set t.status = :toStatus, t.failureReason = :failureReason, t.updatedAt = CURRENT_TIMESTAMP "
                    + "where t.id = :id and t.status = :fromStatus")
    int transitionToFailed(
            @Param("id") UUID id,
            @Param("fromStatus") TransferStatus fromStatus,
            @Param("toStatus") TransferStatus toStatus,
            @Param("failureReason") FailureReason failureReason);

    /** The latest open SCREEN step holds the confirmation deadline. Clearing the persistence
     * context makes the next read of the transfer see the new status, also inside an
     * open-session-in-view request that already loaded it. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            update transfers t set status = 'CLEARED', updated_at = now()
            where t.id = :id and t.status = 'BLOCKED'
              and (select s.deadline from saga_steps s
                   where s.transfer_id = t.id and s.step = :screenStep and s.outcome = :openOutcome
                   order by s.id desc limit 1) >= :now
            """)
    int transitionBlockedToCleared(
            @Param("id") UUID id,
            @Param("now") Instant now,
            @Param("screenStep") String screenStep,
            @Param("openOutcome") String openOutcome);

    // Not a transition: updatedAt stays the moment the transfer entered its status.
    @Modifying
    @Query("update Transfer t set t.releasePending = :pending where t.id = :id")
    int setReleasePending(@Param("id") UUID id, @Param("pending") boolean pending);
}
