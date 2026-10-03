package dev.treyer.sagapay.ledger.application.port.out;

import dev.treyer.sagapay.ledger.domain.LedgerIdempotency;
import dev.treyer.sagapay.ledger.domain.LedgerIdempotencyId;

import java.util.Optional;

public interface LedgerIdempotencyPort {

    /** @return 1 when inserted, 0 when the key already exists. */
    int insertIfAbsent(String transferId, String operation, String resultJson);

    Optional<LedgerIdempotency> findById(LedgerIdempotencyId id);
}
