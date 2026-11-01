package dev.treyer.sagapay.orchestrator.application.port.out;

import java.util.UUID;

public interface ProcessedEventPort {

    /** @return false when this consumer already recorded the event. */
    boolean markProcessed(UUID eventId, String consumer);
}
