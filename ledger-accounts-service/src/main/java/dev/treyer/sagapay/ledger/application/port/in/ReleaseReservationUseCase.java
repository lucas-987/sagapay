package dev.treyer.sagapay.ledger.application.port.in;

import java.util.UUID;

/** Releases nothing when {@code reservationId} is not this transfer's active
 * reservation. */
public interface ReleaseReservationUseCase {
    void releaseReservation(String transferId, UUID reservationId);
}
