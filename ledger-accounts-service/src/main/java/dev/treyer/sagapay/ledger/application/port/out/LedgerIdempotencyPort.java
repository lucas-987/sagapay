package dev.treyer.sagapay.ledger.application.port.out;

import dev.treyer.sagapay.ledger.domain.LedgerIdempotency;
import dev.treyer.sagapay.ledger.domain.LedgerIdempotencyId;

import java.util.Optional;

public interface LedgerIdempotencyPort {

    /** {@code INSERT ... ON CONFLICT DO NOTHING} — @return rows inserted (1 or 0). */
    int insertIfAbsent(String transferId, String operation, String resultJson);

    Optional<LedgerIdempotency> findById(LedgerIdempotencyId id);
}
