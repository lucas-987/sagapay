package dev.treyer.sagapay.orchestrator.domain;

import java.util.UUID;

public class InvalidVerificationCodeException extends RuntimeException {

    public InvalidVerificationCodeException(UUID transferId) {
        super("wrong verification code for transfer " + transferId);
    }
}
