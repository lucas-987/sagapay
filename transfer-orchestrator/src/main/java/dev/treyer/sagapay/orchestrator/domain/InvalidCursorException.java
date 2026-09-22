package dev.treyer.sagapay.orchestrator.domain;

public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
