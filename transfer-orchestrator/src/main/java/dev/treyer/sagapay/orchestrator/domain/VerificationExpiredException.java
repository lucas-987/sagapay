package dev.treyer.sagapay.orchestrator.domain;

import java.util.UUID;

public class VerificationExpiredException extends RuntimeException {

    public VerificationExpiredException(UUID transferId) {
        super("the confirmation window of transfer " + transferId + " has closed");
    }
}
