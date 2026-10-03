package dev.treyer.sagapay.orchestrator.domain;

import java.util.UUID;

public sealed interface ReservationResult {
    record Ok(UUID reservationId) implements ReservationResult {}

    record InsufficientFunds() implements ReservationResult {}
}
