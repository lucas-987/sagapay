package dev.treyer.sagapay.ledger.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** A {@code transferId} replayed with different parameters than the original call. */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String transferId, UUID fromAccountId, BigDecimal amount) {
        super("transferId " + transferId + " already used with different parameters than account "
                + fromAccountId + " / amount " + amount);
    }
}
