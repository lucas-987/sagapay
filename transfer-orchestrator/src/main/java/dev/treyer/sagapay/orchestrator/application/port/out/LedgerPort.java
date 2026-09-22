package dev.treyer.sagapay.orchestrator.application.port.out;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;

import java.util.UUID;

/** The first port {@code out} in this project that talks to another *service*
 * rather than a database — the real implementation ({@code
 * LedgerGrpcClientAdapter}, §5) makes a gRPC call, but the domain ({@code
 * SagaService}) only sees this interface. That's what lets {@code
 * SagaServiceTest} exercise the saga's chaining logic with a plain in-memory
 * {@code FakeLedgerPort} before the gRPC adapter exists at all. */
public interface LedgerPort {

    ReservationResult checkAndReserve(String transferId, UUID fromAccountId, Money amount);

    boolean postTransfer(String transferId, UUID fromAccountId, UUID toAccountId, Money amount);
}
