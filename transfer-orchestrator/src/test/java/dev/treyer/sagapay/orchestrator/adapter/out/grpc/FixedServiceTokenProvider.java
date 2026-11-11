package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * For tests whose ledger ignores tokens ({@code local-noauth}): they need no identity provider.
 * Public: reused by tests in other packages.
 */
public final class FixedServiceTokenProvider implements ServiceTokenProvider {

    private final String token;

    public FixedServiceTokenProvider(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    @Override
    public void drop() {}

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        @Bean
        @Primary
        ServiceTokenProvider fixedServiceTokenProvider() {
            return new FixedServiceTokenProvider("fixed-service-token");
        }
    }
}
