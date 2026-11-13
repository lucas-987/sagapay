package dev.treyer.sagapay.orchestrator.domain;

import java.util.UUID;

/** The caller sent an idempotency key it already used for a different transfer. */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(UUID idempotencyKey) {
        super("idempotency key " + idempotencyKey + " was already used for a different transfer");
    }
}
