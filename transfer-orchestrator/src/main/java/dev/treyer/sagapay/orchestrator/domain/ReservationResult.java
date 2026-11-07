package dev.treyer.sagapay.orchestrator.domain;

import java.time.Instant;
import java.util.UUID;

public sealed interface ReservationResult {
    /** {@code expiresAt} is the ledger's date: past it, the ledger frees the money on its own. */
    record Ok(UUID reservationId, Instant expiresAt) implements ReservationResult {}

    record InsufficientFunds() implements ReservationResult {}
}
