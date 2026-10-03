package dev.treyer.sagapay.orchestrator.domain;

/** The ledger answered, and its answer is a definitive "no" for this exact call
 * (unknown account, currency mismatch, no matching reservation...) -- unlike
 * {@link LedgerUnavailableException}, retrying the same call can never succeed,
 * so the saga fails the transfer instead of leaving it for the reprise poller.
 * Excluded from the circuit breaker's failure count (application.properties): a
 * rejection proves the ledger is up, it must not help open the circuit. */
public class LedgerRejectedException extends RuntimeException {

    private final String ledgerStatus;

    public LedgerRejectedException(String ledgerStatus, String message, Throwable cause) {
        super(message, cause);
        this.ledgerStatus = ledgerStatus;
    }

    /** The gRPC status code name the ledger answered with, e.g. {@code NOT_FOUND}. */
    public String ledgerStatus() {
        return ledgerStatus;
    }
}
