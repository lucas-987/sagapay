package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import com.google.protobuf.Timestamp;
import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveRequest;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveResponse;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import dev.treyer.sagapay.orchestrator.config.LedgerProperties;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What the orchestrator puts in a CheckAndReserve call, read by an in-process ledger. */
class LedgerGrpcClientAdapterRequestTest {

    @Test
    void checkAndReserveCarriesTheSenderTheRecipientAndTheAmount() throws Exception {
        List<CheckAndReserveRequest> received = new ArrayList<>();
        String name = "ledger-" + UUID.randomUUID();
        Server server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new LedgerServiceGrpc.LedgerServiceImplBase() {
                    @Override
                    public void checkAndReserve(
                            CheckAndReserveRequest request, StreamObserver<CheckAndReserveResponse> responseObserver) {
                        received.add(request);
                        responseObserver.onNext(CheckAndReserveResponse.newBuilder()
                                .setStatus(CheckAndReserveResponse.Status.OK)
                                .setReservationId(UUID.randomUUID().toString())
                                .setExpiresAt(Timestamp.newBuilder()
                                        .setSeconds(Instant.now().getEpochSecond() + 300))
                                .build());
                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();
        ManagedChannel channel =
                InProcessChannelBuilder.forName(name).directExecutor().build();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        try {
            new LedgerGrpcClientAdapter(
                            channel,
                            new LedgerProperties(new LedgerProperties.Grpc(5000)),
                            new FixedServiceTokenProvider("service-token"))
                    .checkAndReserve("t-1", from, to, Money.of("12.50", "EUR"));
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }

        assertThat(received).hasSize(1);
        CheckAndReserveRequest request = received.get(0);
        assertThat(request.getTransferId()).isEqualTo("t-1");
        assertThat(request.getFromAccountId()).isEqualTo(from.toString());
        assertThat(request.getToAccountId()).isEqualTo(to.toString());
        assertThat(request.getAmount().getAmount()).isEqualTo("12.5000");
        assertThat(request.getAmount().getCurrency()).isEqualTo("EUR");
    }
}
