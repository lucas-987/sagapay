package dev.treyer.sagapay.orchestrator.domain;

import java.util.UUID;

public class TransferNotBlockedException extends RuntimeException {

    public TransferNotBlockedException(UUID transferId) {
        super("transfer " + transferId + " is not BLOCKED");
    }
}
