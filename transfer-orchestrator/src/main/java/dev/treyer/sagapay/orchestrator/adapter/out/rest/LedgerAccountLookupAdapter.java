package dev.treyer.sagapay.orchestrator.adapter.out.rest;

import dev.treyer.sagapay.orchestrator.application.port.out.AccountLookupPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** REST: the ledger exposes no lookup RPC. */
@Component
class LedgerAccountLookupAdapter implements AccountLookupPort {

    private final RestClient restClient;

    @Autowired
    LedgerAccountLookupAdapter(@Value("${ledger.rest.base-url:http://localhost:8081}") String ledgerBaseUrl) {
        this(RestClient.create(ledgerBaseUrl));
    }

    LedgerAccountLookupAdapter(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Optional<UUID> lookupByHandle(String handle) {
        Map<?, ?> response = restClient
                .get()
                .uri("/v1/users/lookup?handle={handle}", handle)
                .headers(headers -> callerToken().ifPresent(headers::setBearerAuth))
                .exchange((request, resp) -> {
                    if (resp.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(404))) {
                        return null;
                    }
                    return resp.bodyTo(Map.class);
                });
        return response == null ? Optional.empty() : Optional.of(UUID.fromString((String) response.get("accountId")));
    }

    /** The lookup runs inside the caller's request, so it speaks for the caller. */
    private static Optional<String> callerToken() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken caller) {
            return Optional.of(caller.getToken().getTokenValue());
        }
        return Optional.empty();
    }
}
