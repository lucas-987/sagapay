package dev.treyer.sagapay.orchestrator.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface AccountLookupPort {

    Optional<UUID> lookupByHandle(String handle);
}
