package dev.treyer.sagapay.ledger.domain;

import dev.treyer.sagapay.common.domain.Money;

/** Distinct from {@code GetBalanceResponse} (gRPC), which carries only the balance:
 * the two protocols don't have the same needs, hence a separate use case/port rather
 * than one extended one. */
public record WalletSnapshot(Money balance, Money available, Money held) {}
