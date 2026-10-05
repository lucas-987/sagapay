package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.common.v1.Empty;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import dev.treyer.sagapay.ledger.v1.ReleaseReservationRequest;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
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
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No Spring context: a raw channel to the ledger container is all this needs. */
@Testcontainers
class LedgerGrpcClientAdapterTest {

    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final PostgreSQLContainer LEDGER_POSTGRES = new PostgreSQLContainer("postgres:17")
            .withNetwork(NETWORK)
            .withNetworkAliases("ledger-postgres")
            .withDatabaseName("ledger_svc")
            .withUsername("sagapay")
            .withPassword("sagapay-test");

    @Container
    private static final GenericContainer<?> LEDGER = new GenericContainer<>(new ImageFromDockerfile()
                    .withFileFromPath(".", repoRoot())
                    .withDockerfilePath("ledger-accounts-service/Dockerfile"))
            .withNetwork(NETWORK)
            .dependsOn(LEDGER_POSTGRES)
            .withExposedPorts(8081, 9091)
            // local seeds the sample accounts; local-noauth opens REST and gRPC.
            .withEnv(Map.of(
                    "SPRING_PROFILES_ACTIVE", "local,local-noauth",
                    "SPRING_DATASOURCE_URL", "jdbc:postgresql://ledger-postgres:5432/ledger_svc",
                    "SPRING_DATASOURCE_USERNAME", "ledger_app",
                    "SPRING_DATASOURCE_PASSWORD", "ledger-app-test",
                    "SPRING_FLYWAY_URL", "jdbc:postgresql://ledger-postgres:5432/ledger_svc",
                    "SPRING_FLYWAY_USER", "sagapay",
                    "SPRING_FLYWAY_PASSWORD", "sagapay-test",
                    "SPRING_FLYWAY_PLACEHOLDERS_LEDGERAPPUSERNAME", "ledger_app",
                    "SPRING_FLYWAY_PLACEHOLDERS_LEDGERAPPPASSWORD", "ledger-app-test"))
            .waitingFor(Wait.forHttp("/actuator/health").forPort(8081).withStartupTimeout(Duration.ofMinutes(5)));

    private static ManagedChannel channel;
    private static LedgerGrpcClientAdapter adapter;
    private static UUID bobAccountId;
    private static UUID tomaszAccountId;

    // The ledger's Dockerfile builds from the repository root.
    private static Path repoRoot() {
        return Paths.get(System.getProperty("user.dir")).getParent();
    }

    @BeforeAll
    static void setUp() {
        channel = ManagedChannelBuilder.forAddress(LEDGER.getHost(), LEDGER.getMappedPort(9091))
                .usePlaintext()
                .build();
        adapter = new LedgerGrpcClientAdapter(channel, 5000);

        bobAccountId = lookupAccountId("bob");
        tomaszAccountId = lookupAccountId("tomasz");
    }

    private static UUID lookupAccountId(String handle) {
        String lookupUrl = "http://%s:%d/v1/users/lookup?handle=%s"
                .formatted(LEDGER.getHost(), LEDGER.getMappedPort(8081), handle);
        Map<?, ?> response = RestClient.create().get().uri(lookupUrl).retrieve().body(Map.class);
        return UUID.fromString((String) response.get("accountId"));
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
        ReservationResult result =
                adapter.checkAndReserve(UUID.randomUUID().toString(), bobAccountId, Money.of("1.00", "EUR"));

        assertThat(result).isInstanceOf(ReservationResult.Ok.class);
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
    void releaseReservationRoundTripsToARealLedgerContainer() {
        UUID transferId = UUID.randomUUID();
        ReservationResult result =
                adapter.checkAndReserve(transferId.toString(), bobAccountId, Money.of("1.00", "EUR"));
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
            LedgerGrpcClientAdapter refusing = new LedgerGrpcClientAdapter(inProcess, 5000);

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
