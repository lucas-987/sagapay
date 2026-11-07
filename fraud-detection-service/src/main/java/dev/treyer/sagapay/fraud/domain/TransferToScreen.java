package dev.treyer.sagapay.fraud.domain;

import dev.treyer.sagapay.common.domain.Money;

import java.util.UUID;

public record TransferToScreen(UUID recipientId, Money amount) {}
