package dev.treyer.sagapay.orchestrator.adapter.out.rest;

import dev.treyer.sagapay.orchestrator.application.port.out.AccountLookupPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** REST: the ledger exposes no lookup RPC. */
@Component
class LedgerAccountLookupAdapter implements AccountLookupPort {

    private final RestClient restClient;

    LedgerAccountLookupAdapter(@Value("${ledger.rest.base-url:http://localhost:8081}") String ledgerBaseUrl) {
        this.restClient = RestClient.create(ledgerBaseUrl);
    }

    @Override
    public Optional<UUID> lookupByHandle(String handle) {
        Map<?, ?> response = restClient.get()
                .uri("/v1/users/lookup?handle={handle}", handle)
                .exchange((request, resp) -> {
                    if (resp.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(404))) {
                        return null;
                    }
                    return resp.bodyTo(Map.class);
                });
        return response == null ? Optional.empty() : Optional.of(UUID.fromString((String) response.get("accountId")));
    }
}
