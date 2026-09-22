package dev.treyer.sagapay.orchestrator.domain;

/** A request-shaped problem Spring's own binding doesn't already catch (e.g. the
 * {@code X-User-Id} dev/local stand-in for a JWT subject — see {@code
 * TransferRestAdapter}), same role as the ledger's {@code
 * MalformedRequestException}. */
public class MalformedRequestException extends RuntimeException {

    public MalformedRequestException(String field, String value, Throwable cause) {
        super("malformed " + field + ": " + value, cause);
    }

    public MalformedRequestException(String message) {
        super(message);
    }
}
