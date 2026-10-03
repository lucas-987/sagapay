package dev.treyer.sagapay.ledger.application.port.in;

import dev.treyer.sagapay.common.domain.Money;

import java.util.UUID;

public interface GetBalanceUseCase {
    Money getBalance(UUID accountId);
}
