package dev.treyer.sagapay.orchestrator.e2e;

import dev.treyer.sagapay.ledger.v1.CheckAndReserveRequest;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveResponse;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole stack through the REST API, against a real ledger container: money
 * actually moves and the sum of balances is asserted unchanged.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local-noauth")
@TestPropertySource(properties = {"saga.reprise.grace-period-ms=0", "saga.reprise.sweep-interval-ms=1000"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ledger.grpc.deadline-ms=5000")
class TransferOrchestratorE2ETest {

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
                    .withDockerfilePath("ledger-accounts-service/Dockerfile")
                    .withBuildArg("MAVEN_MIRROR_URL", System.getenv().getOrDefault("MAVEN_MIRROR_URL", "")))
            .withNetwork(NETWORK)
            .dependsOn(LEDGER_POSTGRES)
            .withExposedPorts(8081, 9091)
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

    private static Path repoRoot() {
        return Paths.get(System.getProperty("user.dir")).getParent();
    }

    @DynamicPropertySource
    static void ledgerGrpcTarget(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.grpc.client.channel.ledger.target",
                () -> "static://" + LEDGER.getHost() + ":" + LEDGER.getMappedPort(9091));
        registry.add("ledger.rest.base-url", () -> "http://" + LEDGER.getHost() + ":" + LEDGER.getMappedPort(8081));
    }

    @LocalServerPort
    private int orchestratorPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RestClient orchestratorClient;
    private RestClient ledgerClient;
    private static ManagedChannel ledgerChannel;
    private static LedgerServiceGrpc.LedgerServiceBlockingStub ledgerStub;

    @BeforeAll
    static void setUpLedgerGrpcStub() {
        ledgerChannel = ManagedChannelBuilder.forAddress(LEDGER.getHost(), LEDGER.getMappedPort(9091))
                .usePlaintext()
                .build();
        ledgerStub = LedgerServiceGrpc.newBlockingStub(ledgerChannel);
    }

    @AfterAll
    static void tearDownLedgerGrpcStub() {
        if (ledgerChannel != null) {
            ledgerChannel.shutdownNow();
        }
    }

    private RestClient orchestrator() {
        if (orchestratorClient == null) {
            orchestratorClient = RestClient.create("http://localhost:" + orchestratorPort);
        }
        return orchestratorClient;
    }

    private RestClient ledger() {
        if (ledgerClient == null) {
            ledgerClient = RestClient.create("http://" + LEDGER.getHost() + ":" + LEDGER.getMappedPort(8081));
        }
        return ledgerClient;
    }

    private UUID lookupAccountId(String handle) {
        Map<?, ?> response = ledger().get()
                .uri("/v1/users/lookup?handle={handle}", handle)
                .retrieve()
                .body(Map.class);
        return UUID.fromString((String) response.get("accountId"));
    }

    private BigDecimal balanceOf(UUID accountId) {
        Map<?, ?> response = ledger().get()
                .uri("/v1/wallet?accountId={id}", accountId)
                .retrieve()
                .body(Map.class);
        Map<?, ?> balance = (Map<?, ?>) response.get("balance");
        return new BigDecimal((String) balance.get("amount"));
    }

    private Map<?, ?> waitForStatus(UUID transferId, String expectedStatus, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Map<?, ?> last = null;
        while (System.nanoTime() < deadline) {
            last = orchestrator()
                    .get()
                    .uri("/v1/transfers/{id}", transferId)
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .retrieve()
                    .body(Map.class);
            if (expectedStatus.equals(last.get("status"))) {
                return last;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError(
                "transfer " + transferId + " never reached " + expectedStatus + ", last seen: " + last);
    }

    @Test
    void postedTransferMovesMoneyAndKeepsTheSumOfBalancesUnchanged() {
        UUID senderId = lookupAccountId("gina"); // seeded 1200.00 EUR
        UUID recipientId = lookupAccountId("hugo"); // seeded 3400.00 EUR
        BigDecimal senderBefore = balanceOf(senderId);
        BigDecimal recipientBefore = balanceOf(recipientId);

        Map<?, ?> created = orchestrator()
                .post()
                .uri("/v1/transfers")
                .header("X-User-Id", senderId.toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("""
                        {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "80.00"}, "note": "e2e happy path"}""".formatted(recipientId))
                .retrieve()
                .body(Map.class);
        UUID transferId = UUID.fromString((String) created.get("id"));
        assertThat(created.get("status")).isEqualTo("INITIATED");

        waitForStatus(transferId, "POSTED", Duration.ofSeconds(15));

        BigDecimal senderAfter = balanceOf(senderId);
        BigDecimal recipientAfter = balanceOf(recipientId);
        assertThat(senderBefore.subtract(senderAfter)).isEqualByComparingTo("80.00");
        assertThat(recipientAfter.subtract(recipientBefore)).isEqualByComparingTo("80.00");
        assertThat(senderBefore.add(recipientBefore)).isEqualByComparingTo(senderAfter.add(recipientAfter));
    }

    @Test
    void replayingTheSameIdempotencyKeyReturns409AndMovesMoneyOnlyOnce() {
        UUID senderId = lookupAccountId("wojciech"); // seeded 42000.00 EUR
        UUID recipientId = lookupAccountId("lina"); // seeded 99999.99 EUR
        UUID idempotencyKey = UUID.randomUUID();
        BigDecimal senderBefore = balanceOf(senderId);

        String requestBody = """
                {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "25.00"}, "note": "replay test"}""".formatted(recipientId);

        Map<?, ?> first = orchestrator()
                .post()
                .uri("/v1/transfers")
                .header("X-User-Id", senderId.toString())
                .header("Idempotency-Key", idempotencyKey.toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(Map.class);
        UUID transferId = UUID.fromString((String) first.get("id"));
        waitForStatus(transferId, "POSTED", Duration.ofSeconds(15));

        try {
            orchestrator()
                    .post()
                    .uri("/v1/transfers")
                    .header("X-User-Id", senderId.toString())
                    .header("Idempotency-Key", idempotencyKey.toString())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);
            org.junit.jupiter.api.Assertions.fail("expected a 409 on replay");
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(409);
            Map<?, ?> body = e.getResponseBodyAs(Map.class);
            assertThat(body.get("id")).isEqualTo(transferId.toString());
        }

        // Only one 25.00 debit, not two.
        assertThat(senderBefore.subtract(balanceOf(senderId))).isEqualByComparingTo("25.00");
    }

    @Test
    void insufficientFundsFailsTheTransferAndReservesNothing() {
        UUID senderId = lookupAccountId("nora"); // seeded 0.05 EUR
        UUID recipientId = lookupAccountId("marco");
        BigDecimal senderBefore = balanceOf(senderId);

        Map<?, ?> created = orchestrator()
                .post()
                .uri("/v1/transfers")
                .header("X-User-Id", senderId.toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("""
                        {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "80.00"}, "note": "not enough funds"}""".formatted(recipientId))
                .retrieve()
                .body(Map.class);
        UUID transferId = UUID.fromString((String) created.get("id"));

        waitForStatus(transferId, "FAILED", Duration.ofSeconds(15));

        assertThat(balanceOf(senderId)).isEqualByComparingTo(senderBefore); // nothing reserved, nothing moved
    }

    @Test
    void reprisePollerAloneCompletesATransferForcedIntoReservedWithoutTheEagerPath() {
        UUID senderId = lookupAccountId("julien"); // seeded 15000.00 EUR
        UUID recipientId = lookupAccountId("tomasz");
        BigDecimal senderBefore = balanceOf(senderId);
        BigDecimal recipientBefore = balanceOf(recipientId);
        UUID transferId = UUID.randomUUID();
        String ledgerTransferId = transferId.toString();

        // Reserved directly on the ledger: the eager path never runs.
        CheckAndReserveResponse reserved = ledgerStub.checkAndReserve(CheckAndReserveRequest.newBuilder()
                .setTransferId(ledgerTransferId)
                .setFromAccountId(senderId.toString())
                .setAmount(dev.treyer.sagapay.common.v1.Money.newBuilder()
                        .setCurrency("EUR")
                        .setAmount("40.00")
                        .build())
                .build());
        assertThat(reserved.getStatus()).isEqualTo(CheckAndReserveResponse.Status.OK);

        // Only the reprise poller can move this transfer on.
        jdbcTemplate.update(
                """
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency, note, status, reservation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED'::transfer_status, ?)
                """,
                transferId,
                UUID.randomUUID(),
                senderId,
                senderId,
                recipientId,
                recipientId,
                new BigDecimal("40.00"),
                "EUR",
                "crash recovery e2e",
                UUID.fromString(reserved.getReservationId()));

        waitForStatus(transferId, "POSTED", Duration.ofSeconds(15));

        assertThat(senderBefore.subtract(balanceOf(senderId))).isEqualByComparingTo("40.00");
        assertThat(balanceOf(recipientId).subtract(recipientBefore)).isEqualByComparingTo("40.00");
    }

    /** Crash after the ledger reserved, before RESERVED was recorded: the replayed
     * checkAndReserve returns the same reservation instead of holding twice. */
    @Test
    void reprisePollerAloneCompletesATransferLeftInitiatedAfterTheLedgerAlreadyReserved() {
        UUID senderId = lookupAccountId("julien");
        UUID recipientId = lookupAccountId("tomasz");
        BigDecimal senderBefore = balanceOf(senderId);
        BigDecimal recipientBefore = balanceOf(recipientId);
        UUID transferId = UUID.randomUUID();

        CheckAndReserveResponse reserved = ledgerStub.checkAndReserve(CheckAndReserveRequest.newBuilder()
                .setTransferId(transferId.toString())
                .setFromAccountId(senderId.toString())
                .setAmount(dev.treyer.sagapay.common.v1.Money.newBuilder()
                        .setCurrency("EUR")
                        .setAmount("15.00")
                        .build())
                .build());
        assertThat(reserved.getStatus()).isEqualTo(CheckAndReserveResponse.Status.OK);

        jdbcTemplate.update(
                """
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency, note, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'INITIATED'::transfer_status)
                """,
                transferId,
                UUID.randomUUID(),
                senderId,
                senderId,
                recipientId,
                recipientId,
                new BigDecimal("15.00"),
                "EUR",
                "crash before RESERVED e2e");

        waitForStatus(transferId, "POSTED", Duration.ofSeconds(15));

        assertThat(senderBefore.subtract(balanceOf(senderId))).isEqualByComparingTo("15.00");
        assertThat(balanceOf(recipientId).subtract(recipientBefore)).isEqualByComparingTo("15.00");
        assertThat(jdbcTemplate.queryForObject(
                        "select reservation_id from transfers where id = ?", UUID.class, transferId))
                .isEqualTo(UUID.fromString(reserved.getReservationId()));
    }
}
