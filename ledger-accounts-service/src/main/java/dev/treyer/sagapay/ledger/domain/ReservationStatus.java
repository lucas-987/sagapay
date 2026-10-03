package dev.treyer.sagapay.ledger.domain;

/** {@code EXPIRED} is only written by the sweep; the operations themselves check
 * {@code expiresAt}. */
public enum ReservationStatus {
    ACTIVE,
    CONSUMED,
    RELEASED,
    EXPIRED
}
