package dev.treyer.sagapay.orchestrator.application.port.out;

import dev.treyer.sagapay.orchestrator.domain.OutboxRow;

import java.util.List;
import java.util.UUID;

public interface OutboxRelayPort {

    /** Held until the surrounding transaction ends, even on a crash.
     * @return false when another instance is publishing. */
    boolean tryLockPublisher();

    /** Oldest first, without the rows of a quarantined key. */
    List<OutboxRow> findPublishable(int limit);

    void markPublished(UUID id);

    void quarantine(String partitionKey, UUID outboxId, String reason);

    /** 0 when nothing is waiting. */
    double oldestPublishableAgeSeconds();

    long countQuarantinedKeys();
}
