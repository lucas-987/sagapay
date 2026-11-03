package dev.treyer.sagapay.orchestrator.e2e;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** What the end-to-end tests share: a real ledger built from its Dockerfile, and the reads and
 * fake fraud results around the orchestrator. */
final class E2ESupport {

    private E2ESupport() {}

    static PostgreSQLContainer ledgerPostgres(Network network) {
        return new PostgreSQLContainer("postgres:17")
                .withNetwork(network)
                .withNetworkAliases("ledger-postgres")
                .withDatabaseName("ledger_svc")
                .withUsername("sagapay")
                .withPassword("sagapay-test");
    }

    static GenericContainer<?> ledger(Network network, PostgreSQLContainer ledgerPostgres) {
        return new GenericContainer<>(new ImageFromDockerfile()
                        .withFileFromPath(".", repoRoot())
                        .withDockerfilePath("ledger-accounts-service/Dockerfile")
                        .withBuildArg("MAVEN_MIRROR_URL", System.getenv().getOrDefault("MAVEN_MIRROR_URL", "")))
                .withNetwork(network)
                .dependsOn(ledgerPostgres)
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
    }

    private static Path repoRoot() {
        return Paths.get(System.getProperty("user.dir")).getParent();
    }

    static void pointOrchestratorAt(DynamicPropertyRegistry registry, GenericContainer<?> ledger) {
        registry.add(
                "spring.grpc.client.channel.ledger.target",
                () -> "static://" + ledger.getHost() + ":" + ledger.getMappedPort(9091));
        registry.add("ledger.rest.base-url", () -> "http://" + ledger.getHost() + ":" + ledger.getMappedPort(8081));
    }

    static UUID lookupAccountId(RestClient ledgerRest, String handle) {
        Map<?, ?> response = ledgerRest
                .get()
                .uri("/v1/users/lookup?handle={handle}", handle)
                .retrieve()
                .body(Map.class);
        return UUID.fromString((String) response.get("accountId"));
    }

    static BigDecimal balanceOf(RestClient ledgerRest, UUID accountId) {
        return walletAmount(ledgerRest, accountId, "balance");
    }

    static BigDecimal availableOf(RestClient ledgerRest, UUID accountId) {
        return walletAmount(ledgerRest, accountId, "available");
    }

    private static BigDecimal walletAmount(RestClient ledgerRest, UUID accountId, String field) {
        Map<?, ?> response = ledgerRest
                .get()
                .uri("/v1/wallet?accountId={id}", accountId)
                .retrieve()
                .body(Map.class);
        Map<?, ?> money = (Map<?, ?>) response.get(field);
        return new BigDecimal((String) money.get("amount"));
    }

    static Map<?, ?> waitForStatus(
            RestClient orchestratorRest, UUID transferId, String expectedStatus, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Map<?, ?> last = null;
        while (System.nanoTime() < deadline) {
            last = orchestratorRest
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

    /** The fraud service's published result, built by hand: only the fields the orchestrator reads matter. */
    static void publishFraudResult(KafkaTemplate<String, String> kafka, UUID transferId, String decision) {
        String type = "CLEARED".equals(decision) ? "TransferCleared.v1" : "TransferFlagged.v1";
        String score = "CLEARED".equals(decision) ? "0.1500" : "0.9200";
        String event =
                """
                {"id":"%s","source":"fraud-detection-service","type":"%s",\
                "time":"2026-11-01T10:00:00Z","subject":"%s","traceparent":null,\
                "data":{"transferId":"%s","senderId":"%s","decision":"%s","score":%s,\
                "reasons":["AMOUNT_ROUND"],"modelVersion":"v1","ts":"2026-11-01T10:00:00Z"}}""".formatted(UUID.randomUUID(), type, transferId, transferId, UUID.randomUUID(), decision, score);
        kafka.send("payments.fraud.results", transferId.toString(), event).join();
    }

    /** A result that arrives before the screening step is recorded and ignored, so the transfer must be
     * screened first. */
    static void publishFraudResultOnceScreening(
            RestClient orchestratorRest, KafkaTemplate<String, String> kafka, UUID transferId, String decision) {
        waitForStatus(orchestratorRest, transferId, "SCREENING", Duration.ofSeconds(15));
        publishFraudResult(kafka, transferId, decision);
    }
}
