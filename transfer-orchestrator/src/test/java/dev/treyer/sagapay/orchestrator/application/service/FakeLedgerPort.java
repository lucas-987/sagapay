package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class FakeLedgerPort implements LedgerPort {

    private ReservationResult reservationResult = new ReservationResult.Ok(UUID.randomUUID());
    private RuntimeException reservationFailure;
    private boolean postResult = true;
    private RuntimeException postFailure;
    private int postTransferCallCount = 0;
    private RuntimeException releaseFailure;
    private final List<UUID> releasedReservations = new ArrayList<>();

    void willReserve(ReservationResult result) {
        this.reservationResult = result;
        this.reservationFailure = null;
    }

    void willFailReserve(RuntimeException failure) {
        this.reservationFailure = failure;
    }

    void willPost(boolean result) {
        this.postResult = result;
        this.postFailure = null;
    }

    void willFailPost(RuntimeException failure) {
        this.postFailure = failure;
    }

    void willFailRelease(RuntimeException failure) {
        this.releaseFailure = failure;
    }

    /** The bean is shared by the whole test class. */
    void reset() {
        willReserve(new ReservationResult.Ok(UUID.randomUUID()));
        willPost(true);
        postTransferCallCount = 0;
        releaseFailure = null;
        releasedReservations.clear();
    }

    /** Reservation ids of every release call, failed ones included. */
    List<UUID> releaseCalls() {
        return releasedReservations;
    }

    int postTransferCallCount() {
        return postTransferCallCount;
    }

    @Override
    public ReservationResult checkAndReserve(String transferId, UUID fromAccountId, Money amount) {
        if (reservationFailure != null) {
            throw reservationFailure;
        }
        return reservationResult;
    }

    @Override
    public boolean postTransfer(String transferId, UUID fromAccountId, UUID toAccountId, Money amount) {
        postTransferCallCount++;
        if (postFailure != null) {
            throw postFailure;
        }
        return postResult;
    }

    @Override
    public void releaseReservation(String transferId, UUID reservationId) {
        releasedReservations.add(reservationId);
        if (releaseFailure != null) {
            throw releaseFailure;
        }
    }
}
