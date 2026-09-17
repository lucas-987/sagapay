package dev.treyer.sagapay.ledger.domain;

import java.util.UUID;

/** Deliberately reduced to {@code accountId}/{@code displayName}, never {@code
 * balance}/{@code currency}: it's a "who is this", not a wallet read — the caller is
 * looking up a transfer's recipient before sending money, not their balance. */
public record AccountLookup(UUID accountId, String displayName) {}
