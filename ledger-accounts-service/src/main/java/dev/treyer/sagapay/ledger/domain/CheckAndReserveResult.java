package dev.treyer.sagapay.ledger.domain;

import java.time.Instant;
import java.util.UUID;

public sealed interface CheckAndReserveResult {
    record Ok(UUID reservationId, Instant expiresAt) implements CheckAndReserveResult {}

    record InsufficientFunds() implements CheckAndReserveResult {}
}
