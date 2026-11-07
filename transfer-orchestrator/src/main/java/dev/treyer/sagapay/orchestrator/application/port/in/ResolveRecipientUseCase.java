package dev.treyer.sagapay.orchestrator.application.port.in;

import java.util.UUID;

public interface ResolveRecipientUseCase {

    /** @param handle with or without its leading {@code @}
     * @throws dev.treyer.sagapay.orchestrator.domain.RecipientNotFoundException no account has this handle */
    UUID resolveByHandle(String handle);
}
