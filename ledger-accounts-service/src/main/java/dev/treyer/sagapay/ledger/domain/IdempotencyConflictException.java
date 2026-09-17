package dev.treyer.sagapay.ledger.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** A {@code transferId} already memoized in {@code ledger_idempotency} is replayed
 * with a different {@code fromAccountId}/{@code amount} than the original call. Maps
 * to {@code AppErrorCode.DUPLICATE_OPERATION} — not a missing resource or an invalid
 * input, but a conflict with state already written. */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String transferId, UUID fromAccountId, BigDecimal amount) {
        super("transferId " + transferId + " already used with different parameters than account "
                + fromAccountId + " / amount " + amount);
    }
}
