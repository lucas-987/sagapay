package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

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
    List<Transfer> findByStatusAndUpdatedAtBefore(TransferStatus status, Instant cutoff);

    /** The latest RELEASE step decides, by id: several steps written in one
     * transaction share the same {@code now()}. */
    @Query(nativeQuery = true, value = """
            select t.* from transfers t
            where t.status = 'FAILED'::transfer_status
              and t.updated_at < :cutoff
              and (select s.outcome from saga_steps s
                   where s.transfer_id = t.id and s.step = 'RELEASE'
                   order by s.id desc limit 1) = 'RETRY'
            """)
    List<Transfer> findFailedWithPendingRelease(@Param("cutoff") Instant cutoff);

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

    /** @return 0 when a replay already created the transfer. Unlike a caught
     * constraint violation, {@code ON CONFLICT DO NOTHING} leaves the transaction
     * usable for the read-back. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into transfers (id, idempotency_key, sender_id, sender_account_id, recipient_id,
                recipient_account_id, amount, currency, note, status, created_at, updated_at)
            values (:id, :idempotencyKey, :senderId, :senderAccountId, :recipientId, :recipientAccountId,
                :amount, :currency, :note, 'INITIATED'::transfer_status, now(), now())
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
    @Query(
            "update Transfer t set t.status = :toStatus, t.reservationId = :reservationId, t.updatedAt = CURRENT_TIMESTAMP "
                    + "where t.id = :id and t.status = :fromStatus")
    int transitionWithReservation(
            @Param("id") UUID id,
            @Param("fromStatus") TransferStatus fromStatus,
            @Param("toStatus") TransferStatus toStatus,
            @Param("reservationId") UUID reservationId);

    @Modifying
    @Query("update Transfer t set t.status = :toStatus, t.updatedAt = CURRENT_TIMESTAMP "
            + "where t.id = :id and t.status = :fromStatus")
    int transitionStatus(
            @Param("id") UUID id,
            @Param("fromStatus") TransferStatus fromStatus,
            @Param("toStatus") TransferStatus toStatus);

    @Modifying
    @Query(
            "update Transfer t set t.status = :toStatus, t.failureReason = :failureReason, t.updatedAt = CURRENT_TIMESTAMP "
                    + "where t.id = :id and t.status = :fromStatus")
    int transitionToFailed(
            @Param("id") UUID id,
            @Param("fromStatus") TransferStatus fromStatus,
            @Param("toStatus") TransferStatus toStatus,
            @Param("failureReason") String failureReason);
}
