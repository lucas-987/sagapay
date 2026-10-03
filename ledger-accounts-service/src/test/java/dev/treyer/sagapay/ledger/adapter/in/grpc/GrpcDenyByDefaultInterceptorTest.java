package dev.treyer.sagapay.ledger.adapter.in.grpc;

import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import dev.treyer.sagapay.ledger.v1.GetBalanceRequest;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.GrpcChannelFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Without {@code local-noauth}, calls are rejected before the handler: even an
 * unknown account gets UNAUTHENTICATED, not NOT_FOUND. */
@SpringBootTest
@AutoConfigureTestGrpcTransport
@Import(TestcontainersConfiguration.class)
class GrpcDenyByDefaultInterceptorTest {

    @Autowired
    private GrpcChannelFactory channelFactory;

    @Test
    void grpcCallIsRejectedByDefaultWithoutLocalNoauthProfile() {
        var client = LedgerServiceGrpc.newBlockingStub(channelFactory.createChannel("test"));

        assertThatThrownBy(() -> client.getBalance(GetBalanceRequest.newBuilder()
                        .setAccountId(UUID.randomUUID().toString())
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("UNAUTHENTICATED");
    }
}
