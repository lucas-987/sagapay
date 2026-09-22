package dev.treyer.sagapay.orchestrator.domain;

public class RecipientNotFoundException extends RuntimeException {

    public RecipientNotFoundException(String handleOrId) {
        super("no such recipient: " + handleOrId);
    }
}
