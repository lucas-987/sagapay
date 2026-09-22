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
    private boolean postResult = true;
    private int postTransferCallCount = 0;

    void willReserve(ReservationResult result) {
        this.reservationResult = result;
    }

    void willPost(boolean result) {
        this.postResult = result;
    }

    int postTransferCallCount() {
        return postTransferCallCount;
    }

    @Override
    public ReservationResult checkAndReserve(String transferId, UUID fromAccountId, Money amount) {
        return reservationResult;
    }

    @Override
    public boolean postTransfer(String transferId, UUID fromAccountId, UUID toAccountId, Money amount) {
        postTransferCallCount++;
        return postResult;
    }
}
