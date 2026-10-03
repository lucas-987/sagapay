package dev.treyer.sagapay.orchestrator.domain;

/** A failed call or an open circuit: either way the ledger is not answering. */
public class LedgerUnavailableException extends RuntimeException {

    public LedgerUnavailableException(Throwable cause) {
        super("ledger unavailable", cause);
    }
}
