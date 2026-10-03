package dev.treyer.sagapay.ledger.application.port.out;

import dev.treyer.sagapay.ledger.domain.Reservation;
import dev.treyer.sagapay.ledger.domain.ReservationStatus;

import java.math.BigDecimal;
import java.util.UUID;

public interface ReservationPort {

    void save(Reservation reservation);

    BigDecimal sumAmountByAccountIdAndStatus(UUID accountId, ReservationStatus status);

    /** @return 0 when the status differs from {@code fromStatus} or the reservation
     * is not this transfer's. */
    int updateStatusByReservationId(String transferId, UUID reservationId,
                                     ReservationStatus fromStatus, ReservationStatus toStatus);

    /** Makes a debit conditional on a matching reservation, not just on the balance.
     * @return 0 when no active, unexpired reservation matches exactly. */
    int consumeIfMatching(String transferId, UUID accountId, BigDecimal amount,
                           ReservationStatus fromStatus, ReservationStatus toStatus);

    /** Data hygiene only: reads already ignore expired reservations through
     * {@code expiresAt}. */
    int expireOverdue();
}
