package dev.treyer.sagapay.orchestrator.adapter.in.rest;

public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
