package dev.treyer.sagapay.orchestrator.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Resolves a {@code @handle} to a ledger account id, for {@code
 * CreateTransferRequest.toHandle} (§8, stretch scope). The real adapter calls
 * the ledger's own {@code GET /v1/users/lookup} over REST -- there's no gRPC
 * equivalent ({@code ledger.proto} has no lookup RPC). */
public interface AccountLookupPort {

    Optional<UUID> lookupByHandle(String handle);
}
