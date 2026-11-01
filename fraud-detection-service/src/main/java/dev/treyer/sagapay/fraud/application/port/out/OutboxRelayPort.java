package dev.treyer.sagapay.fraud.application.port.out;

import dev.treyer.sagapay.fraud.domain.OutboxMessage;

import java.util.List;
import java.util.UUID;

public interface OutboxRelayPort {

    /** Locks the rows it returns until the surrounding transaction ends; rows locked elsewhere are skipped. */
    List<OutboxMessage> findUnpublishedForUpdateSkipLocked(int limit);

    void markPublished(UUID id);
}
