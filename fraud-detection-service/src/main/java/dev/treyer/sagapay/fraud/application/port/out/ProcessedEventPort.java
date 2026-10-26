package dev.treyer.sagapay.fraud.application.port.out;

public interface ProcessedEventPort {

    /** @return false when the event was already processed by this consumer. */
    boolean markProcessed(String eventId, String consumer);
}
