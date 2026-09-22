package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
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

    /** Used by {@code SagaReprisePoller} (§7) to find transfers the eager direct
     * path never finished -- {@code updatedAt} doubles as "since when has this
     * been RESERVED" because nothing else touches a RESERVED row's timestamp. */
    List<Transfer> findByStatusAndUpdatedAtBefore(TransferStatus status, Instant cutoff);

    /** 1 row inserted = this call is first, its own outbox write is the one to
     * make. 0 rows = a concurrent replay (or the client's own retry) already won
     * — same insert-first idiom as the ledger's {@code ledger_idempotency}, here
     * applied directly to the business row since {@code transfers} carries its
     * own idempotency constraint (no separate table needed). Native query, not
     * {@code save()}: {@code ON CONFLICT DO NOTHING} has no JPQL equivalent, and
     * unlike a caught {@code DataIntegrityViolationException} it never aborts the
     * surrounding transaction, so the read-back below can run in the same
     * {@code @Transactional} method. {@code status} is a literal, not a bind
     * parameter: every row is born {@code INITIATED}, so there's nothing to cast. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into transfers (id, idempotency_key, sender_id, sender_account_id, recipient_id,
                recipient_account_id, amount, currency, note, status, created_at, updated_at)
            values (:id, :idempotencyKey, :senderId, :senderAccountId, :recipientId, :recipientAccountId,
                :amount, :currency, :note, 'INITIATED'::transfer_status, now(), now())
            on conflict (sender_id, idempotency_key) do nothing
            """)
    int insertIfAbsent(@Param("id") UUID id, @Param("idempotencyKey") UUID idempotencyKey,
                        @Param("senderId") UUID senderId, @Param("senderAccountId") UUID senderAccountId,
                        @Param("recipientId") UUID recipientId, @Param("recipientAccountId") UUID recipientAccountId,
                        @Param("amount") BigDecimal amount, @Param("currency") String currency,
                        @Param("note") String note);

    /** JPQL, not native SQL: Hibernate's own type system (the {@code
     * @JdbcTypeCode(NAMED_ENUM)} mapping on {@code Transfer.status}) handles
     * binding these enum parameters against the native Postgres enum column,
     * same as it already does for {@code save()} — no manual {@code ::}` cast
     * needed here, unlike {@link #insertIfAbsent}. */
    @Modifying
    @Query("update Transfer t set t.status = :toStatus, t.reservationId = :reservationId, t.updatedAt = CURRENT_TIMESTAMP "
            + "where t.id = :id and t.status = :fromStatus")
    int transitionWithReservation(@Param("id") UUID id, @Param("fromStatus") TransferStatus fromStatus,
                                   @Param("toStatus") TransferStatus toStatus, @Param("reservationId") UUID reservationId);

    @Modifying
    @Query("update Transfer t set t.status = :toStatus, t.updatedAt = CURRENT_TIMESTAMP "
            + "where t.id = :id and t.status = :fromStatus")
    int transitionStatus(@Param("id") UUID id, @Param("fromStatus") TransferStatus fromStatus,
                          @Param("toStatus") TransferStatus toStatus);

    @Modifying
    @Query("update Transfer t set t.status = :toStatus, t.failureReason = :failureReason, t.updatedAt = CURRENT_TIMESTAMP "
            + "where t.id = :id and t.status = :fromStatus")
    int transitionToFailed(@Param("id") UUID id, @Param("fromStatus") TransferStatus fromStatus,
                            @Param("toStatus") TransferStatus toStatus, @Param("failureReason") String failureReason);
}
