package dev.treyer.sagapay.orchestrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/** Read with {@code @Value}: configuration properties binding leaves an unresolved placeholder in
 * the value instead of failing, which would start the service with the placeholder as secret. */
@Configuration
class LedgerAuthConfig {

    @Bean
    LedgerAuthProperties ledgerAuthProperties(
            @Value("${ledger.auth.client-id}") String clientId,
            @Value("${ledger.auth.client-secret}") String clientSecret,
            @Value("${ledger.auth.token-uri}") URI tokenUri,
            @Value("${ledger.auth.refresh-skew-ms}") long refreshSkewMs,
            @Value("${ledger.auth.request-timeout-ms}") long requestTimeoutMs) {
        return new LedgerAuthProperties(clientId, clientSecret, tokenUri, refreshSkewMs, requestTimeoutMs);
    }
}
