package dev.treyer.sagapay.orchestrator.application.port.out;

import dev.treyer.sagapay.orchestrator.domain.OutboxRow;

import java.util.List;
import java.util.UUID;

public interface OutboxRelayPort {

    /** Locks the rows it returns until the surrounding transaction ends; rows locked elsewhere are skipped. */
    List<OutboxRow> findUnpublishedForUpdateSkipLocked(int limit);

    void markPublished(UUID id);
}
