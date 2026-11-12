package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import dev.treyer.sagapay.orchestrator.security.TestTokens;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.function.Supplier;

/**
 * A service token with no identity provider to fetch it from. Public: reused by tests in other
 * packages.
 */
public final class FixedServiceTokenProvider implements ServiceTokenProvider {

    private final Supplier<String> token;

    /** For a ledger that is not a real one: it never reads the token. */
    public FixedServiceTokenProvider(String token) {
        this(() -> token);
    }

    private FixedServiceTokenProvider(Supplier<String> token) {
        this.token = token;
    }

    /** For a real ledger: a token signed with the key it trusts, issued afresh at every call so
     * that it never lapses during a long test. */
    public static FixedServiceTokenProvider acceptedByTheLedger() {
        return new FixedServiceTokenProvider(TestTokens::ledgerServiceToken);
    }

    @Override
    public String token() {
        return token.get();
    }

    @Override
    public void drop() {}

    /** The provider of the tests that call a real ledger container ({@code LedgerContainer}). */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        @Bean
        @Primary
        ServiceTokenProvider fixedServiceTokenProvider() {
            return acceptedByTheLedger();
        }
    }
}
