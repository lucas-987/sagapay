package dev.treyer.sagapay.ledger.domain;

import dev.treyer.sagapay.common.domain.Money;

public record WalletSnapshot(Money balance, Money available, Money held) {}
