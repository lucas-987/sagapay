package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 * Needs the Spring-proxied bean: {@code @CircuitBreaker} is applied through AOP.
 * Ordered because the last test stops the shared ledger container for good.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LedgerCircuitBreakerTest {

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

    private static UUID bobAccountId;

    private static Path repoRoot() {
        return Paths.get(System.getProperty("user.dir")).getParent();
    }

    @DynamicPropertySource
    static void ledgerGrpcTarget(DynamicPropertyRegistry registry) {
        registry.add("spring.grpc.client.channel.ledger.target",
                () -> "static://" + LEDGER.getHost() + ":" + LEDGER.getMappedPort(9091));
    }

    @BeforeAll
    static void resolveBobAccountId() {
        String lookupUrl = "http://%s:%d/v1/users/lookup?handle=bob".formatted(LEDGER.getHost(), LEDGER.getMappedPort(8081));
        Map<?, ?> response = RestClient.create().get().uri(lookupUrl).retrieve().body(Map.class);
        bobAccountId = UUID.fromString((String) response.get("accountId"));
    }

    @Autowired
    private LedgerPort ledgerPort;
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Test
    @Order(1)
    void ledgerRejectionsSurfaceAsIsAndNeverOpenTheCircuit() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("ledger");
        // More than the breaker's minimum number of calls.
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> ledgerPort.postTransfer(
                    UUID.randomUUID().toString(), bobAccountId, bobAccountId, Money.of("1.00", "EUR")))
                    .isInstanceOf(LedgerRejectedException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @Order(2)
    void killingTheLedgerContainerOpensTheCircuitAndFallsBackToLedgerUnavailable() {
        ReservationResult before = ledgerPort.checkAndReserve(
                UUID.randomUUID().toString(), bobAccountId, Money.of("1.00", "EUR"));
        assertThat(before).isInstanceOf(ReservationResult.Ok.class);

        LEDGER.stop();

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("ledger");
        for (int i = 0; i < 5 && circuitBreaker.getState() != CircuitBreaker.State.OPEN; i++) {
            assertThatThrownBy(() -> ledgerPort.checkAndReserve(
                    UUID.randomUUID().toString(), bobAccountId, Money.of("1.00", "EUR")))
                    .isInstanceOf(LedgerUnavailableException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> ledgerPort.checkAndReserve(
                UUID.randomUUID().toString(), bobAccountId, Money.of("1.00", "EUR")))
                .isInstanceOf(LedgerUnavailableException.class);
    }
}
