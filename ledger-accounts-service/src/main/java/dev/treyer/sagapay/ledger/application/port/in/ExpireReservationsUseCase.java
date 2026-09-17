package dev.treyer.sagapay.ledger.application.port.in;

/**
 * No RPC or endpoint calls this — the caller is the {@code @Scheduled} sweep adapter,
 * but the business rule for what counts as "overdue" belongs here, not in the adapter.
 */
public interface ExpireReservationsUseCase {
    /** @return number of reservations transitioned ACTIVE -> EXPIRED. */
    int expireOverdueReservations();
}
