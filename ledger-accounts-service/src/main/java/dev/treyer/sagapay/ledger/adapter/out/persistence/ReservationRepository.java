package dev.treyer.sagapay.ledger.adapter.out.persistence;

import dev.treyer.sagapay.ledger.domain.Reservation;
import dev.treyer.sagapay.ledger.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Optional<Reservation> findByTransferId(String transferId);

    /** Filters {@code expiresAt} directly instead of relying on the scheduled sweep
     * ({@code ReservationExpirySweeper}) to have already flipped the status: an
     * abandoned hold must stop counting the moment it expires, not just at the
     * sweep's next run. */
    @Query("select coalesce(sum(r.amount), 0) from Reservation r "
            + "where r.accountId = :accountId and r.status = :status and r.expiresAt > CURRENT_TIMESTAMP")
    BigDecimal sumAmountByAccountIdAndStatus(@Param("accountId") UUID accountId,
                                              @Param("status") ReservationStatus status);

    /** Guarded by {@code id} on top of {@code transferId}: a mismatched
     * {@code reservationId} also yields 0 rows, not just an already-changed status —
     * makes this idempotent without needing a {@code ledger_idempotency} entry. */
    @Modifying
    @Query("update Reservation r set r.status = :toStatus "
            + "where r.transferId = :transferId and r.id = :reservationId and r.status = :fromStatus")
    int updateStatusByReservationId(@Param("transferId") String transferId,
                                     @Param("reservationId") UUID reservationId,
                                     @Param("fromStatus") ReservationStatus fromStatus,
                                     @Param("toStatus") ReservationStatus toStatus);

    /** Like {@link #updateStatusByReservationId}, but guarded by {@code accountId}
     * and {@code amount} instead of {@code reservationId}: {@code postTransfer}
     * doesn't have a reservation id to key off of, only the transfer's declared
     * account/amount. */
    @Modifying
    @Query("update Reservation r set r.status = :toStatus "
            + "where r.transferId = :transferId and r.accountId = :accountId and r.amount = :amount "
            + "and r.status = :fromStatus and r.expiresAt > CURRENT_TIMESTAMP")
    int consumeIfMatching(@Param("transferId") String transferId, @Param("accountId") UUID accountId,
                           @Param("amount") BigDecimal amount,
                           @Param("fromStatus") ReservationStatus fromStatus,
                           @Param("toStatus") ReservationStatus toStatus);

    /** No {@code SELECT ... FOR UPDATE SKIP LOCKED}: there's no per-row side effect
     * to run exactly once here, just an idempotent status flip, so a plain bulk
     * {@code UPDATE} is safe even if several instances run it concurrently. */
    @Modifying
    @Query("update Reservation r set r.status = :toStatus "
            + "where r.status = :fromStatus and r.expiresAt <= CURRENT_TIMESTAMP")
    int expireOverdue(@Param("fromStatus") ReservationStatus fromStatus, @Param("toStatus") ReservationStatus toStatus);
}
