package dev.treyer.sagapay.ledger.domain;

import java.util.UUID;

/** Sealed rather than a {@code Status} enum plus a nullable {@code reservationId}
 * field: the compiler forces both cases to be handled (no forgotten {@code default}
 * in a future {@code switch}), and {@code Ok} can't exist without its {@code
 * reservationId}. */
public sealed interface CheckAndReserveResult {
    record Ok(UUID reservationId) implements CheckAndReserveResult {}
    record InsufficientFunds() implements CheckAndReserveResult {}
}
