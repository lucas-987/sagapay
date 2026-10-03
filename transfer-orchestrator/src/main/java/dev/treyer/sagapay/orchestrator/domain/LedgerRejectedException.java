package dev.treyer.sagapay.orchestrator.domain;

/** A definitive refusal: retrying cannot succeed, so the saga fails the transfer.
 * Ignored by the circuit breaker, since it proves the ledger is up. */
public class LedgerRejectedException extends RuntimeException {

    private final String ledgerStatus;

    public LedgerRejectedException(String ledgerStatus, String message, Throwable cause) {
        super(message, cause);
        this.ledgerStatus = ledgerStatus;
    }

    public String ledgerStatus() {
        return ledgerStatus;
    }
}
