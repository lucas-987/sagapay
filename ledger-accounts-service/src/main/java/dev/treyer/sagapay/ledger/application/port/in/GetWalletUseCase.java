package dev.treyer.sagapay.ledger.application.port.in;

import dev.treyer.sagapay.ledger.domain.WalletSnapshot;

import java.util.UUID;

public interface GetWalletUseCase {
    WalletSnapshot getWallet(UUID accountId);
}
