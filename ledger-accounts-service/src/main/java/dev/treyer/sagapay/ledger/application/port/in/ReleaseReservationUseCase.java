package dev.treyer.sagapay.ledger.application.port.in;

import java.util.UUID;

/**
 * Takes {@code reservationId} alongside {@code transferId} as an integrity guard:
 * releasing fails silently if {@code reservationId} doesn't match this {@code
 * transferId}'s active reservation, rather than trusting the caller on that point.
 */
public interface ReleaseReservationUseCase {
    void releaseReservation(String transferId, UUID reservationId);
}
