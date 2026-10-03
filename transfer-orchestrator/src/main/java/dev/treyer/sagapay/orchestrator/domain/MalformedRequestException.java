package dev.treyer.sagapay.orchestrator.domain;

/** Request problems that Spring's binding does not catch, such as a bad
 * {@code X-User-Id}. */
public class MalformedRequestException extends RuntimeException {

    public MalformedRequestException(String field, String value, Throwable cause) {
        super("malformed " + field + ": " + value, cause);
    }

    public MalformedRequestException(String message) {
        super(message);
    }
}
