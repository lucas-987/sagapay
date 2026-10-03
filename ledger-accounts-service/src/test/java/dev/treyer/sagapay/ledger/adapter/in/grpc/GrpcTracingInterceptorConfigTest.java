package dev.treyer.sagapay.ledger.adapter.in.grpc;

import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import dev.treyer.sagapay.ledger.adapter.out.persistence.AccountRepository;
import dev.treyer.sagapay.ledger.domain.Account;
import dev.treyer.sagapay.ledger.v1.GetBalanceRequest;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

/** The test registry replaces the auto-configured one, which backs off through
 * {@code @ConditionalOnMissingBean}. */
@SpringBootTest
@AutoConfigureTestGrpcTransport
@ActiveProfiles("local-noauth")
@Import(TestcontainersConfiguration.class)
class GrpcTracingInterceptorConfigTest {

    @Autowired
    private GrpcChannelFactory channelFactory;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TestObservationRegistry observationRegistry;

    @Test
    void grpcCallProducesAnObservation() {
        UUID accountId = accounts.save(
                        new Account("test-" + UUID.randomUUID(), "Test User", "EUR", new BigDecimal("10.0000")))
                .getId();
        var client = LedgerServiceGrpc.newBlockingStub(channelFactory.createChannel("test"));

        client.getBalance(GetBalanceRequest.newBuilder()
                .setAccountId(accountId.toString())
                .build());

        // Not an exact count: the cached context shares this registry with other
        // test classes and the sweeper.
        TestObservationRegistryAssert.assertThat(observationRegistry).hasObservationWithNameEqualTo("grpc.server");
    }

    @TestConfiguration
    static class ObservationTestConfig {
        @Bean
        TestObservationRegistry observationRegistry() {
            return TestObservationRegistry.create();
        }
    }
}
