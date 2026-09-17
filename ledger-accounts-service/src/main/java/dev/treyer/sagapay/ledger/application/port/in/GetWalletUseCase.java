package dev.treyer.sagapay.ledger.application.port.in;

import dev.treyer.sagapay.ledger.domain.WalletSnapshot;

import java.util.UUID;

/**
 * Kept separate from {@link GetBalanceUseCase} rather than folding {@code available}/
 * {@code held} into it: that one's only caller is the gRPC adapter, whose proto
 * response has no room for those fields anyway.
 */
public interface GetWalletUseCase {
    WalletSnapshot getWallet(UUID accountId);
}
