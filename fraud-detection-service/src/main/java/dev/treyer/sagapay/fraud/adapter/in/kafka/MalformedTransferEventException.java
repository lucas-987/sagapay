package dev.treyer.sagapay.fraud.adapter.in.kafka;

/** Retrying cannot fix the event: the error handler sends it straight to the dead-letter topic. */
public class MalformedTransferEventException extends RuntimeException {

    public MalformedTransferEventException(String message) {
        super(message);
    }
}
