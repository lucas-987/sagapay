package dev.treyer.sagapay.ledger.domain;

import java.util.UUID;

public sealed interface CheckAndReserveResult {
    record Ok(UUID reservationId) implements CheckAndReserveResult {}

    record InsufficientFunds() implements CheckAndReserveResult {}
}
