package dev.treyer.sagapay.ledger.application.port.in;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.ledger.domain.CheckAndReserveResult;

import java.util.UUID;

public interface CheckAndReserveUseCase {
    CheckAndReserveResult checkAndReserve(String transferId, UUID fromAccountId, Money amount);
}
