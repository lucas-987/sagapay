package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

/**
 * First test in this project that starts the container of an *other* service
 * (the ledger, whose Dockerfile already exists since M1) rather than only its
 * own — new ground, per the M2 checklist: no {@code @SpringBootTest} here on
 * purpose, this only needs a raw gRPC channel and the adapter under test, not
 * the orchestrator's own Postgres/Kafka/RabbitMQ infrastructure.
 */
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
            // local: seeds demo accounts (bob among them). local-noauth: opens
            // both REST and gRPC without a JWT -- no Keycloak in this test.
            .withEnv(Map.of(
                    "SPRING_PROFILES_ACTIVE", "local,local-noauth",
                    "SPRING_DATASOURCE_URL", "jdbc:postgresql://ledger-postgres:5432/ledger_svc",
                    "SPRING_DATASOURCE_USERNAME", "ledger_app",
                    "SPRING_DATASOURCE_PASSWORD", "ledger-app-test",
                    "SPRING_FLYWAY_URL", "jdbc:postgresql://ledger-postgres:5432/ledger_svc",
                    "SPRING_FLYWAY_USER", "sagapay",
                    "SPRING_FLYWAY_PASSWORD", "sagapay-test",
                    "SPRING_FLYWAY_PLACEHOLDERS_LEDGERAPPUSERNAME", "ledger_app",
                    "SPRING_FLYWAY_PLACEHOLDERS_LEDGERAPPPASSWORD", "ledger-app-test"
            ))
            .waitingFor(Wait.forHttp("/actuator/health").forPort(8081).withStartupTimeout(Duration.ofMinutes(5)));

    private static ManagedChannel channel;
    private static LedgerGrpcClientAdapter adapter;
    private static UUID bobAccountId;
    private static UUID tomaszAccountId;

    // Repo-root build context: the ledger's Dockerfile does `COPY . .` and builds
    // the whole Maven reactor (-am), so it needs to see common/contracts/etc,
    // not just its own module directory.
    private static Path repoRoot() {
        return Paths.get(System.getProperty("user.dir")).getParent();
    }

    @BeforeAll
    static void setUp() {
        channel = ManagedChannelBuilder.forAddress(LEDGER.getHost(), LEDGER.getMappedPort(9091))
                .usePlaintext()
                .build();
        adapter = new LedgerGrpcClientAdapter(channel);

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
        // bob is seeded with 4.50 EUR (LocalAccountSeeder) -- 1.00 fits.
        ReservationResult result = adapter.checkAndReserve(
                UUID.randomUUID().toString(), bobAccountId, Money.of("1.00", "EUR"));

        assertThat(result).isInstanceOf(ReservationResult.Ok.class);
    }

    /** What the saga gets when a reservation expired before postTransfer: the
     * ledger's definitive NOT_FOUND must surface as a rejection, not be confused
     * with the ledger being down. */
    @Test
    void postTransferWithoutAMatchingReservationIsARejectionNotAnOutage() {
        assertThatThrownBy(() -> adapter.postTransfer(
                UUID.randomUUID().toString(), bobAccountId, tomaszAccountId, Money.of("1.00", "EUR")))
                .isInstanceOfSatisfying(LedgerRejectedException.class,
                        e -> assertThat(e.ledgerStatus()).isEqualTo("NOT_FOUND"));
    }
}
