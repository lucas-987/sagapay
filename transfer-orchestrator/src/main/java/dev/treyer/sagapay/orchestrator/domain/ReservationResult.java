package dev.treyer.sagapay.orchestrator.domain;

import java.util.UUID;

/** Sealed rather than a status enum plus a nullable reservationId field, same
 * reasoning as the ledger's {@code CheckAndReserveResult}: the compiler forces
 * both cases to be handled, and {@code Ok} can't exist without its id. */
public sealed interface ReservationResult {
    record Ok(UUID reservationId) implements ReservationResult {}
    record InsufficientFunds() implements ReservationResult {}
}
