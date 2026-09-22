package dev.treyer.sagapay.orchestrator.domain;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException(UUID transferId) {
        super("unknown transfer " + transferId);
    }
}
