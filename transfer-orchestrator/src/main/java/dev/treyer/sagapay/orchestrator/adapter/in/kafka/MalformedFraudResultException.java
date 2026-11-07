package dev.treyer.sagapay.orchestrator.adapter.in.kafka;

/** Retrying cannot fix the record: the error handler sends it straight to the dead-letter topic. */
public class MalformedFraudResultException extends RuntimeException {

    public MalformedFraudResultException(String message, Throwable cause) {
        super(message, cause);
    }

    public MalformedFraudResultException(String message) {
        super(message);
    }
}
