package dev.treyer.sagapay.ledger.application.port.in;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.ledger.domain.CheckAndReserveResult;

import java.util.UUID;

/**
 * Takes a full {@link Money} (amount + currency), not a bare {@code BigDecimal} — the
 * currency needs to reach the domain so it can be checked against the account's.
 */
public interface CheckAndReserveUseCase {
    CheckAndReserveResult checkAndReserve(String transferId, UUID fromAccountId, Money amount);
}
