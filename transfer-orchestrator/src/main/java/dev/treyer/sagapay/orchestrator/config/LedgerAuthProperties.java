package dev.treyer.sagapay.orchestrator.config;

import java.net.URI;

/**
 * The orchestrator's own credential for the ledger. Client id and secret have no default: a
 * missing or blank value must stop the start, not leave the saga without a credential.
 */
public record LedgerAuthProperties(
        String clientId, String clientSecret, URI tokenUri, long refreshSkewMs, long requestTimeoutMs) {

    public LedgerAuthProperties {
        require(clientId != null && !clientId.isBlank(), "ledger.auth.client-id must not be blank");
        require(clientSecret != null && !clientSecret.isBlank(), "ledger.auth.client-secret must not be blank");
        require(tokenUri != null, "ledger.auth.token-uri must be set");
        require(refreshSkewMs > 0, "ledger.auth.refresh-skew-ms must be positive");
        require(requestTimeoutMs > 0, "ledger.auth.request-timeout-ms must be positive");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    // The generated one would print the secret in any log line or message that shows the properties.
    @Override
    public String toString() {
        return "LedgerAuthProperties[clientId=" + clientId + ", clientSecret=***, tokenUri=" + tokenUri + "]";
    }
}
