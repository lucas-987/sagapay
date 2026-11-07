package dev.treyer.sagapay.orchestrator.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The stack exactly as {@code infra/docker-compose.yml} runs it, driven over HTTP the way the
 * README does. Only the secrets and the confirmation window are given through the environment.
 *
 * <p>The compose file publishes fixed host ports (8081 to 8083, 5432 to 5434, 6379, 29092) and
 * the build takes minutes: the ports must be free, and the class must not run in parallel with
 * another stack or build.
 */
class LocalStackFraudFlowE2ETest {

    private static final String VERIFICATION_CODE = "compose-verification-code";
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final int LEDGER_PORT = 8081;
    private static final int ORCHESTRATOR_PORT = 8082;
    private static final int FRAUD_PORT = 8083;

    private static ComposeContainer stack;
    private static RestClient ledger;
    private static RestClient orchestrator;

    @BeforeAll
    static void startStack() {
        File compose = Paths.get(System.getProperty("user.dir"))
                .getParent()
                .resolve("infra/docker-compose.yml")
                .toFile();
        stack = new ComposeContainer(compose)
                .withBuild(true)
                .withEnv(Map.of(
                        "MAVEN_MIRROR_URL", System.getenv().getOrDefault("MAVEN_MIRROR_URL", ""),
                        "SAGAPAY_DB_PASSWORD", "compose-db-password",
                        "LEDGER_APP_PASSWORD", "compose-ledger-password",
                        "ORCHESTRATOR_APP_PASSWORD", "compose-orchestrator-password",
                        "FRAUD_APP_PASSWORD", "compose-fraud-password",
                        "SAGAPAY_FRAUD_VERIFICATION_CODE", VERIFICATION_CODE,
                        "SAGA_CONFIRMATION_WINDOW_MS", "10000"))
                .withExposedService(
                        "ledger-accounts-service",
                        LEDGER_PORT,
                        Wait.forHttp("/actuator/health").withStartupTimeout(Duration.ofMinutes(10)))
                .withExposedService(
                        "transfer-orchestrator",
                        ORCHESTRATOR_PORT,
                        Wait.forHttp("/actuator/health").withStartupTimeout(Duration.ofMinutes(10)))
                .withExposedService(
                        "fraud-detection-service",
                        FRAUD_PORT,
                        Wait.forHttp("/actuator/health").withStartupTimeout(Duration.ofMinutes(10)));
        stack.start();
        ledger = clientOf("ledger-accounts-service", LEDGER_PORT);
        orchestrator = clientOf("transfer-orchestrator", ORCHESTRATOR_PORT);
    }

    @AfterAll
    static void stopStack() {
        if (stack != null) {
            stack.stop();
        }
    }

    private static RestClient clientOf(String service, int port) {
        return RestClient.create(
                "http://" + stack.getServiceHost(service, port) + ":" + stack.getServicePort(service, port));
    }

    private static UUID flaggedTransfer(UUID senderId, UUID recipientId) {
        Map<?, ?> created = orchestrator
                .post()
                .uri("/v1/transfers")
                .header("X-User-Id", senderId.toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "500.00"}}""".formatted(recipientId))
                .retrieve()
                .body(Map.class);
        return UUID.fromString((String) created.get("id"));
    }

    private static int confirm(UUID transferId, UUID senderId) {
        return orchestrator
                .post()
                .uri("/v1/transfers/{id}/confirm", transferId)
                .header("X-User-Id", senderId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"verificationToken\": \"%s\"}".formatted(VERIFICATION_CODE))
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value();
    }

    @Test
    @DisplayName("Every service of the stack reports UP")
    void everyServiceReportsUp() {
        assertThat(healthOf("ledger-accounts-service", LEDGER_PORT)).isEqualTo(Map.of("status", "UP"));
        assertThat(healthOf("transfer-orchestrator", ORCHESTRATOR_PORT)).isEqualTo(Map.of("status", "UP"));
        assertThat(healthOf("fraud-detection-service", FRAUD_PORT)).isEqualTo(Map.of("status", "UP"));
    }

    private static Map<?, ?> healthOf(String service, int port) {
        // Only the top-level status is the contract here; component details vary by service.
        Map<?, ?> body =
                clientOf(service, port).get().uri("/actuator/health").retrieve().body(Map.class);
        return Map.of("status", body.get("status"));
    }

    @Test
    @Tag("M3-S42")
    @DisplayName("A flagged transfer confirmed with the configured code is posted")
    void flaggedTransferConfirmedWithTheConfiguredCodeIsPosted() {
        UUID senderId = E2ESupport.lookupAccountId(ledger, "marco");
        UUID recipientId = E2ESupport.lookupAccountId(ledger, "ines");

        UUID transferId = flaggedTransfer(senderId, recipientId);
        E2ESupport.waitForStatus(orchestrator, transferId, senderId, "BLOCKED", WAIT);

        assertThat(confirm(transferId, senderId)).isEqualTo(202);
        E2ESupport.waitForStatus(orchestrator, transferId, senderId, "POSTED", WAIT);
    }

    @Test
    @Tag("M3-S43")
    @DisplayName("A flagged transfer left alone expires")
    void flaggedTransferLeftAloneExpires() {
        UUID senderId = E2ESupport.lookupAccountId(ledger, "marco");
        UUID recipientId = E2ESupport.lookupAccountId(ledger, "hugo");

        UUID transferId = flaggedTransfer(senderId, recipientId);
        Map<?, ?> failed = E2ESupport.waitForStatus(orchestrator, transferId, senderId, "FAILED", WAIT);

        assertThat(failed.get("failureReason")).isEqualTo("VERIFICATION_EXPIRED");
        assertThatThrownBy(() -> confirm(transferId, senderId))
                .isInstanceOfSatisfying(HttpClientErrorException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(410);
                    assertThat(e.getResponseBodyAs(Map.class).get("code")).isEqualTo("VERIFICATION_EXPIRED");
                });
    }
}
