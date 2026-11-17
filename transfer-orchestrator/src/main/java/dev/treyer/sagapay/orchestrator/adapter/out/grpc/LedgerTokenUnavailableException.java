package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

/** The identity provider gave no token. The message names the failure, never a credential. */
public class LedgerTokenUnavailableException extends RuntimeException {

    public LedgerTokenUnavailableException(String message) {
        super(message);
    }

    public LedgerTokenUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
