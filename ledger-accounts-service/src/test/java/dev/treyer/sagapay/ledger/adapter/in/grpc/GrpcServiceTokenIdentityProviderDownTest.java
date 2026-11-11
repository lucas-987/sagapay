package dev.treyer.sagapay.ledger.adapter.in.grpc;

import dev.treyer.sagapay.common.v1.Money;
import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import dev.treyer.sagapay.ledger.adapter.out.persistence.AccountRepository;
import dev.treyer.sagapay.ledger.domain.Account;
import dev.treyer.sagapay.ledger.security.KeySetServer;
import dev.treyer.sagapay.ledger.security.TestTokens;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveRequest;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveResponse;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keys are cached, so the ledger keeps admitting valid tokens while the identity provider is
 * down. Its own class, with its own key server, because the test stops that server for good.
 */
@SpringBootTest
@AutoConfigureTestGrpcTransport
@Import(TestcontainersConfiguration.class)
class GrpcServiceTokenIdentityProviderDownTest {

    private static final String RECIPIENT = UUID.randomUUID().toString();

    private static final KeySetServer IDENTITY_PROVIDER = new KeySetServer();

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokens.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IDENTITY_PROVIDER::url);
    }

    @AfterAll
    static void stopIdentityProvider() {
        IDENTITY_PROVIDER.stop();
    }

    @Autowired
    private GrpcChannelFactory channelFactory;

    @Autowired
    private AccountRepository accounts;

    private CheckAndReserveResponse reserve(UUID accountId) {
        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + TestTokens.token().build());
        return LedgerServiceGrpc.newBlockingStub(channelFactory.createChannel("test"))
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .checkAndReserve(CheckAndReserveRequest.newBuilder()
                        .setTransferId(UUID.randomUUID().toString())
                        .setFromAccountId(accountId.toString())
                        .setToAccountId(RECIPIENT)
                        .setAmount(Money.newBuilder()
                                .setCurrency("EUR")
                                .setAmount("40.00")
                                .build())
                        .build());
    }

    @Test
    void theLedgerKeepsAcceptingValidTokensWhileTheIdentityProviderIsDown() {
        UUID accountId = accounts.save(
                        new Account("test-" + UUID.randomUUID(), "Test User", "EUR", new BigDecimal("100.0000")))
                .getId();
        reserve(accountId);
        IDENTITY_PROVIDER.stop();

        CheckAndReserveResponse response = reserve(accountId);

        assertThat(response.getStatus()).isEqualTo(CheckAndReserveResponse.Status.OK);
    }
}
