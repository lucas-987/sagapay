package dev.treyer.sagapay.ledger.application.port.out;

import dev.treyer.sagapay.ledger.domain.Reservation;
import dev.treyer.sagapay.ledger.domain.ReservationStatus;

import java.math.BigDecimal;
import java.util.UUID;

public interface ReservationPort {

    void save(Reservation reservation);

    /** Sum of the account's active holds — used to compute {@code available}. */
    BigDecimal sumAmountByAccountIdAndStatus(UUID accountId, ReservationStatus status);

    /**
     * Transition by {@code (transferId, reservationId)}, not {@code transferId}
     * alone — the extra check is an integrity guard against a caller passing a
     * reservation that isn't actually this transfer's.
     * @return number of rows affected: 0 = {@code fromStatus} no longer matches, OR
     * {@code reservationId} doesn't match this {@code transferId}'s reservation.
     */
    int updateStatusByReservationId(String transferId, UUID reservationId,
                                     ReservationStatus fromStatus, ReservationStatus toStatus);

    /**
     * Guard that makes a debit conditional on a matching reservation, not just on
     * the account's raw balance — without it, {@code postTransfer} could debit an
     * account that never went through {@code checkAndReserve}.
     * @return number of rows affected: 0 = no ACTIVE, non-expired reservation
     * matches this exact {@code (transferId, accountId, amount)}.
     */
    int consumeIfMatching(String transferId, UUID accountId, BigDecimal amount,
                           ReservationStatus fromStatus, ReservationStatus toStatus);

    /**
     * Bulk-transitions every reservation past its {@code expiresAt} from {@code
     * ACTIVE} to {@code EXPIRED}. Pure data hygiene: {@code checkAndReserve} and
     * {@code postTransfer} already exclude expired reservations via {@code
     * expiresAt} directly, independent of whether this sweep has run.
     * @return number of rows transitioned.
     */
    int expireOverdue();
}
