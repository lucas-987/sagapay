package dev.treyer.sagapay.orchestrator.domain;

import java.time.Instant;
import java.util.UUID;

/** A position rather than an offset, stable under concurrent writes; {@code
 * createdAt} never changes. */
public record TransferCursor(Instant createdAt, UUID id) {

    public static TransferCursor of(Transfer transfer) {
        return new TransferCursor(transfer.getCreatedAt(), transfer.getId());
    }
}
