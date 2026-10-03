package dev.treyer.sagapay.orchestrator.application.port.out;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;

import java.util.UUID;

public interface LedgerPort {

    ReservationResult checkAndReserve(String transferId, UUID fromAccountId, Money amount);

    boolean postTransfer(String transferId, UUID fromAccountId, UUID toAccountId, Money amount);
}
