package dev.treyer.sagapay.ledger.adapter.in.grpc;

import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import dev.treyer.sagapay.ledger.security.KeySetServer;
import dev.treyer.sagapay.ledger.security.TestTokens;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import dev.treyer.sagapay.ledger.v1.ReleaseReservationRequest;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A ledger that never read the realm's keys cannot tell a good token from a bad one: it refuses
 * the call as unauthenticated, like any token it cannot verify.
 */
@SpringBootTest
@AutoConfigureTestGrpcTransport
@Import(TestcontainersConfiguration.class)
class GrpcServiceTokenKeysUnreadableTest {

    private static final String UNREACHABLE_KEY_SET = unreachableKeySet();

    private static String unreachableKeySet() {
        KeySetServer stopped = new KeySetServer();
        String url = stopped.url();
        stopped.stop();
        return url;
    }

    @DynamicPropertySource
    static void realm(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokens.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> UNREACHABLE_KEY_SET);
    }

    @Autowired
    private GrpcChannelFactory channelFactory;

    @Test
    void aValidTokenIsRefusedAsUnauthenticatedWhenTheKeysCannotBeRead() {
        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + TestTokens.token().build());

        assertThatThrownBy(() -> LedgerServiceGrpc.newBlockingStub(channelFactory.createChannel("test"))
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                        .releaseReservation(ReleaseReservationRequest.newBuilder()
                                .setTransferId(UUID.randomUUID().toString())
                                .setReservationId(UUID.randomUUID().toString())
                                .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }
}
