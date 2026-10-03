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

    /** Filters on {@code expiresAt} rather than the status: an expired hold must stop
     * counting immediately, not at the next sweep. */
    @Query("select coalesce(sum(r.amount), 0) from Reservation r "
            + "where r.accountId = :accountId and r.status = :status and r.expiresAt > CURRENT_TIMESTAMP")
    BigDecimal sumAmountByAccountIdAndStatus(
            @Param("accountId") UUID accountId, @Param("status") ReservationStatus status);

    /** Matching on {@code id} as well makes a wrong reservation id a no-op, and the
     * status guard makes replays idempotent. */
    @Modifying
    @Query("update Reservation r set r.status = :toStatus "
            + "where r.transferId = :transferId and r.id = :reservationId and r.status = :fromStatus")
    int updateStatusByReservationId(
            @Param("transferId") String transferId,
            @Param("reservationId") UUID reservationId,
            @Param("fromStatus") ReservationStatus fromStatus,
            @Param("toStatus") ReservationStatus toStatus);

    /** Keyed on account and amount: {@code postTransfer} has no reservation id. */
    @Modifying
    @Query("update Reservation r set r.status = :toStatus "
            + "where r.transferId = :transferId and r.accountId = :accountId and r.amount = :amount "
            + "and r.status = :fromStatus and r.expiresAt > CURRENT_TIMESTAMP")
    int consumeIfMatching(
            @Param("transferId") String transferId,
            @Param("accountId") UUID accountId,
            @Param("amount") BigDecimal amount,
            @Param("fromStatus") ReservationStatus fromStatus,
            @Param("toStatus") ReservationStatus toStatus);

    /** A plain bulk update is safe across instances: the status flip is idempotent
     * and has no per-row side effect. */
    @Modifying
    @Query("update Reservation r set r.status = :toStatus "
            + "where r.status = :fromStatus and r.expiresAt <= CURRENT_TIMESTAMP")
    int expireOverdue(@Param("fromStatus") ReservationStatus fromStatus, @Param("toStatus") ReservationStatus toStatus);
}
