package dev.treyer.sagapay.ledger.domain;

import java.util.UUID;

public record AccountLookup(UUID accountId, String displayName) {}
