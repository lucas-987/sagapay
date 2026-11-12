package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.common.v1.Empty;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveRequest;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveResponse;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import dev.treyer.sagapay.ledger.v1.ReleaseReservationRequest;
import dev.treyer.sagapay.orchestrator.config.LedgerProperties;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import dev.treyer.sagapay.orchestrator.security.LedgerContainer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No Spring context: a raw channel to the ledger container is all this needs. */
@Testcontainers
class LedgerGrpcClientAdapterTest {

    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final PostgreSQLContainer LEDGER_POSTGRES = LedgerContainer.postgres(NETWORK);

    @Container
    private static final GenericContainer<?> LEDGER = LedgerContainer.create(NETWORK, LEDGER_POSTGRES);

    private static ManagedChannel channel;
    private static LedgerGrpcClientAdapter adapter;
    private static UUID bobAccountId;
    private static UUID tomaszAccountId;

    @BeforeAll
    static void setUp() {
        channel = ManagedChannelBuilder.forAddress(LEDGER.getHost(), LEDGER.getMappedPort(9091))
                .usePlaintext()
                .build();
        adapter = new LedgerGrpcClientAdapter(
                channel,
                new LedgerProperties(new LedgerProperties.Grpc(5000)),
                FixedServiceTokenProvider.acceptedByTheLedger());

        bobAccountId = lookupAccountId("bob");
        tomaszAccountId = lookupAccountId("tomasz");
    }

    private static UUID lookupAccountId(String handle) {
        return LedgerContainer.lookupAccountId(LEDGER, handle);
    }

    @AfterAll
    static void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @Test
    void checkAndReserveRoundTripsToARealLedgerContainer() {
        // bob is seeded with 4.50 EUR.
        ReservationResult result = adapter.checkAndReserve(
                UUID.randomUUID().toString(), bobAccountId, tomaszAccountId, Money.of("1.00", "EUR"));

        assertThat(result).isInstanceOf(ReservationResult.Ok.class);
        assertThat(((ReservationResult.Ok) result).expiresAt()).isAfter(Instant.now());
    }

    @Test
    void anAnswerWithoutItsExpiryIsNotTakenForAReservation() throws Exception {
        String serverName = "ledger-without-expiry";
        Server server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new LedgerServiceGrpc.LedgerServiceImplBase() {
                    @Override
                    public void checkAndReserve(
                            CheckAndReserveRequest request, StreamObserver<CheckAndReserveResponse> responseObserver) {
                        responseObserver.onNext(CheckAndReserveResponse.newBuilder()
                                .setStatus(CheckAndReserveResponse.Status.OK)
                                .setReservationId(UUID.randomUUID().toString())
                                .build());
                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();
        ManagedChannel inProcess =
                InProcessChannelBuilder.forName(serverName).directExecutor().build();
        try {
            LedgerGrpcClientAdapter adapterOfAnOldLedger = new LedgerGrpcClientAdapter(
                    inProcess,
                    new LedgerProperties(new LedgerProperties.Grpc(5000)),
                    new FixedServiceTokenProvider("service-token"));

            assertThatThrownBy(() -> adapterOfAnOldLedger.checkAndReserve(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            Money.of("1.00", "EUR")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("without its expiry");
        } finally {
            inProcess.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    void postTransferWithoutAMatchingReservationIsARejectionNotAnOutage() {
        assertThatThrownBy(() -> adapter.postTransfer(
                        UUID.randomUUID().toString(), bobAccountId, tomaszAccountId, Money.of("1.00", "EUR")))
                .isInstanceOfSatisfying(
                        LedgerRejectedException.class,
                        e -> assertThat(e.ledgerStatus()).isEqualTo("NOT_FOUND"));
    }

    @Test
    void postTransferToTheReservedRecipientIsAccepted() {
        String transferId = UUID.randomUUID().toString();
        adapter.checkAndReserve(transferId, bobAccountId, tomaszAccountId, Money.of("1.00", "EUR"));

        assertThatCode(() -> adapter.postTransfer(transferId, bobAccountId, tomaszAccountId, Money.of("1.00", "EUR")))
                .doesNotThrowAnyException();
    }

    @Test
    void releaseReservationRoundTripsToARealLedgerContainer() {
        UUID transferId = UUID.randomUUID();
        ReservationResult result =
                adapter.checkAndReserve(transferId.toString(), bobAccountId, tomaszAccountId, Money.of("1.00", "EUR"));
        UUID reservationId = ((ReservationResult.Ok) result).reservationId();

        adapter.releaseReservation(transferId.toString(), reservationId);
        // The ledger answers success for a hold that is already released.
        adapter.releaseReservation(transferId.toString(), reservationId);
    }

    @ParameterizedTest
    @EnumSource(
            value = Status.Code.class,
            names = {"NOT_FOUND", "INVALID_ARGUMENT", "ALREADY_EXISTS"})
    void releaseReservationRefusalCodesBecomeALedgerRejection(Status.Code code) throws Exception {
        String serverName = "ledger-refusing-" + code;
        Server server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new LedgerServiceGrpc.LedgerServiceImplBase() {
                    @Override
                    public void releaseReservation(
                            ReleaseReservationRequest request, StreamObserver<Empty> responseObserver) {
                        responseObserver.onError(
                                code.toStatus().withDescription("refused").asRuntimeException());
                    }
                })
                .build()
                .start();
        ManagedChannel inProcess =
                InProcessChannelBuilder.forName(serverName).directExecutor().build();
        try {
            LedgerGrpcClientAdapter refusing = new LedgerGrpcClientAdapter(
                    inProcess,
                    new LedgerProperties(new LedgerProperties.Grpc(5000)),
                    new FixedServiceTokenProvider("service-token"));

            assertThatThrownBy(
                            () -> refusing.releaseReservation(UUID.randomUUID().toString(), UUID.randomUUID()))
                    .isInstanceOfSatisfying(LedgerRejectedException.class, e -> {
                        assertThat(e.ledgerStatus()).isEqualTo(code.name());
                        assertThat(e.getMessage()).isEqualTo("refused");
                    });
        } finally {
            inProcess.shutdownNow();
            server.shutdownNow();
        }
    }
}
