package dev.treyer.sagapay.fraud.application.port.out;

import java.util.UUID;

public interface ProcessedEventPort {

    /** @return false when the event was already processed by this consumer. */
    boolean tryMarkProcessed(UUID eventId, String consumer);
}
