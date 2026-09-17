package dev.treyer.sagapay.ledger.application.port.in;

import dev.treyer.sagapay.common.domain.Money;

import java.util.UUID;

/** Returns a full {@link Money} (amount + currency) — the caller needs the currency too. */
public interface GetBalanceUseCase {
    Money getBalance(UUID accountId);
}
