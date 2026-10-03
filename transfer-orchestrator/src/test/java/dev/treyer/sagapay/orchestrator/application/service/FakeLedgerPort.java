package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;

import java.util.UUID;

/** A plain Java test double, not Mockito: {@code SagaService} tests exercise the
 * saga's chaining logic against a configurable, call-counting fake rather than
 * a real gRPC call to the ledger (that round trip is {@code
 * LedgerGrpcClientAdapterTest}'s job, §5). */
class FakeLedgerPort implements LedgerPort {

    private ReservationResult reservationResult = new ReservationResult.Ok(UUID.randomUUID());
    private RuntimeException reservationFailure;
    private boolean postResult = true;
    private RuntimeException postFailure;
    private int postTransferCallCount = 0;

    void willReserve(ReservationResult result) {
        this.reservationResult = result;
        this.reservationFailure = null;
    }

    /** E.g. a {@code LedgerRejectedException} or {@code LedgerUnavailableException}. */
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

    /** The bean is shared by every test of the class: back to the happy path. */
    void reset() {
        willReserve(new ReservationResult.Ok(UUID.randomUUID()));
        willPost(true);
        postTransferCallCount = 0;
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
}
