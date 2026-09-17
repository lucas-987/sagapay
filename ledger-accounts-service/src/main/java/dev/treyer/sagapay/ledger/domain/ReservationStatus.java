package dev.treyer.sagapay.ledger.domain;

/** A reservation's lifecycle — mapped to {@code VARCHAR(16)} via @Enumerated(STRING).
 * {@code EXPIRED} is written only by the scheduled sweep ({@code
 * ReservationExpirySweeper}), never by {@code checkAndReserve}/{@code postTransfer}/
 * {@code releaseReservation} themselves: those already exclude an expired
 * reservation from their calculations/guards via {@code expiresAt}, without waiting
 * for its status to be updated. */
public enum ReservationStatus {
    ACTIVE,
    CONSUMED,
    RELEASED,
    EXPIRED
}
