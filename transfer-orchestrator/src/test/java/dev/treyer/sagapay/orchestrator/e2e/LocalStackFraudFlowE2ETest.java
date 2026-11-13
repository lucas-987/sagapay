package dev.treyer.sagapay.orchestrator.e2e;

import dev.treyer.sagapay.orchestrator.adapter.in.rest.ActivityStreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The stack exactly as {@code infra/docker-compose.yml} runs it, driven over HTTP the way the
 * README does. Only the secrets and the confirmation window are given through the environment.
 *
 * <p>The compose file publishes fixed host ports (8081 to 8083, 8180, 5432 to 5434, 6379, 29092) and
 * the build takes minutes: the ports must be free, and the class must not run in parallel with
 * another stack or build.
 */
class LocalStackFraudFlowE2ETest {

    private static final String VERIFICATION_CODE = "compose-verification-code";
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final int GATEWAY_PORT = 8080;
    // Published on 8080 by default; the test takes any free host port, which a developer machine
    // may already use for something else. The gateway listens on 8080 inside the network either way.
    private static final int GATEWAY_HOST_PORT = freePort();
    private static final int LEDGER_PORT = 8081;
    private static final int ORCHESTRATOR_PORT = 8082;
    private static final int FRAUD_PORT = 8083;
    private static final int KEYCLOAK_PORT = 8180;
    private static final String ISSUER = "http://keycloak:8180/realms/sagapay";

    private static ComposeContainer stack;
    private static RestClient gateway;
    private static RestClient ledger;
    private static RestClient orchestrator;
    private static RestClient keycloak;

    @BeforeAll
    static void startStack() {
        File compose = Paths.get(System.getProperty("user.dir"))
                .getParent()
                .resolve("infra/docker-compose.yml")
                .toFile();
        stack = new ComposeContainer(compose)
                .withBuild(true)
                .withEnv(Map.ofEntries(
                        Map.entry("MAVEN_MIRROR_URL", System.getenv().getOrDefault("MAVEN_MIRROR_URL", "")),
                        Map.entry("SAGAPAY_DB_PASSWORD", "compose-db-password"),
                        Map.entry("LEDGER_APP_PASSWORD", "compose-ledger-password"),
                        Map.entry("ORCHESTRATOR_APP_PASSWORD", "compose-orchestrator-password"),
                        Map.entry("FRAUD_APP_PASSWORD", "compose-fraud-password"),
                        Map.entry("KEYCLOAK_ADMIN_PASSWORD", "compose-keycloak-password"),
                        Map.entry("KEYCLOAK_ENV_FILE", sampleUserPasswords().toString()),
                        Map.entry("ORCHESTRATOR_CLIENT_ID", "transfer-orchestrator"),
                        Map.entry("ORCHESTRATOR_CLIENT_SECRET", "compose-client-secret"),
                        Map.entry("SAGAPAY_FRAUD_VERIFICATION_CODE", VERIFICATION_CODE),
                        Map.entry("SAGA_CONFIRMATION_WINDOW_MS", "10000"),
                        Map.entry("GATEWAY_HOST_PORT", String.valueOf(GATEWAY_HOST_PORT))))
                .withExposedService(
                        "api-gateway",
                        GATEWAY_PORT,
                        Wait.forHttp("/actuator/health").withStartupTimeout(Duration.ofMinutes(10)))
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
                        Wait.forHttp("/actuator/health").withStartupTimeout(Duration.ofMinutes(10)))
                .withExposedService(
                        "keycloak",
                        KEYCLOAK_PORT,
                        Wait.forHttp("/realms/sagapay").withStartupTimeout(Duration.ofMinutes(10)));
        stack.start();
        gateway = clientOf("api-gateway", GATEWAY_PORT);
        ledger = clientOf("ledger-accounts-service", LEDGER_PORT);
        orchestrator = clientOf("transfer-orchestrator", ORCHESTRATOR_PORT);
        keycloak = clientOf("keycloak", KEYCLOAK_PORT);
    }

    /** Each sample user's password is their handle, in the file format Compose reads. */
    private static Path sampleUserPasswords() {
        try {
            Path file = Files.createTempFile("keycloak", ".env");
            file.toFile().deleteOnExit();
            return Files.writeString(file, "MARCO_PASSWORD=marco\nINES_PASSWORD=ines\nHUGO_PASSWORD=hugo\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void stopStack() {
        if (stack != null) {
            stack.stop();
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String baseUrlOf(String service, int port) {
        return "http://" + stack.getServiceHost(service, port) + ":" + stack.getServicePort(service, port);
    }

    private static RestClient clientOf(String service, int port) {
        return RestClient.create(baseUrlOf(service, port));
    }

    private static UUID flaggedTransfer(String token, UUID recipientId) {
        return send(orchestrator, token, recipientId, "500.00");
    }

    private static UUID send(RestClient through, String token, UUID recipientId, String amount) {
        Map<?, ?> created = through.post()
                .uri("/v1/transfers")
                .headers(headers -> headers.setBearerAuth(token))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "%s"}}""".formatted(recipientId, amount))
                .retrieve()
                .body(Map.class);
        return UUID.fromString((String) created.get("id"));
    }

    private static int confirm(UUID transferId, String token) {
        return confirm(orchestrator, transferId, token, VERIFICATION_CODE);
    }

    private static int confirm(RestClient through, UUID transferId, String token, String code) {
        return through.post()
                .uri("/v1/transfers/{id}/confirm", transferId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"verificationToken\": \"%s\"}".formatted(code))
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

    private static String tokenFor(String handle) {
        Map<?, ?> token = keycloak.post()
                .uri("/realms/sagapay/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=password&client_id=sagapay-cli&username=%s&password=%s".formatted(handle, handle))
                .retrieve()
                .body(Map.class);
        return (String) token.get("access_token");
    }

    private static Map<?, ?> claimsOf(String jwt) {
        byte[] payload = Base64.getUrlDecoder().decode(jwt.split("\\.")[1]);
        return JsonMapper.builder().build().readValue(payload, Map.class);
    }

    @Test
    @DisplayName("A token fetched for marco has the id of marco's account as subject")
    void tokenFetchedForMarcoHasTheIdOfMarcosAccountAsSubject() {
        UUID marcoAccountId = E2ESupport.lookupAccountId(ledger, "marco", tokenFor("marco"));

        Map<?, ?> claims = claimsOf(tokenFor("marco"));

        assertThat(claims.get("sub")).isEqualTo(marcoAccountId.toString());
    }

    @Test
    @DisplayName("The issuer is the same from the host and from inside the network")
    void issuerIsTheSameFromTheHostAndFromInsideTheNetwork() throws Exception {
        String fromHost = (String) keycloak.get()
                .uri("/realms/sagapay/.well-known/openid-configuration")
                .retrieve()
                .body(Map.class)
                .get("issuer");
        // Keycloak's image has no curl: bash opens the connection, by the Compose name.
        String insideAnswer = stack.getContainerByServiceName("keycloak")
                .orElseThrow()
                .execInContainer(
                        "bash",
                        "-c",
                        "exec 3<>/dev/tcp/keycloak/8180;"
                                + " printf 'GET /realms/sagapay/.well-known/openid-configuration HTTP/1.0\\r\\nHost: keycloak:8180\\r\\n\\r\\n' >&3;"
                                + " cat <&3")
                .getStdout();
        String fromInside = insideAnswer.replaceAll("(?s).*\"issuer\":\"([^\"]*)\".*", "$1");

        assertThat(fromHost).isEqualTo(ISSUER);
        assertThat(fromInside).isEqualTo(ISSUER);
        assertThat(claimsOf(tokenFor("marco")).get("iss")).isEqualTo(ISSUER);
    }

    @Test
    @DisplayName("The ledger answers a wallet read with the token owner's wallet, whatever account is named")
    void ledgerAnswersAWalletReadWithTheTokenOwnersWallet() {
        String marcoToken = tokenFor("marco");
        UUID marcoAccountId = E2ESupport.lookupAccountId(ledger, "marco", marcoToken);
        UUID inesAccountId = E2ESupport.lookupAccountId(ledger, "ines", marcoToken);

        Map<?, ?> wallet = ledger.get()
                .uri("/v1/wallet?accountId={id}", inesAccountId)
                .headers(headers -> headers.setBearerAuth(marcoToken))
                .retrieve()
                .body(Map.class);

        assertThat(wallet.get("accountId")).isEqualTo(marcoAccountId.toString());
    }

    private enum Service {
        TRANSFER_SERVICE("/v1/transfers"),
        LEDGER("/v1/wallet");

        private final String path;

        Service(String path) {
            this.path = path;
        }

        RestClient client() {
            return this == LEDGER ? ledger : orchestrator;
        }
    }

    private enum Credential {
        NO_TOKEN(null),
        GARBAGE_TOKEN("not-a-jwt");

        private final String bearer;

        Credential(String bearer) {
            this.bearer = bearer;
        }
    }

    private static Stream<Arguments> serviceAndCredential() {
        return Stream.of(Service.values())
                .flatMap(
                        service -> Stream.of(Credential.values()).map(credential -> Arguments.of(service, credential)));
    }

    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("serviceAndCredential")
    @Tag("M4-S05")
    @DisplayName("A service called directly refuses a call without a valid token")
    void serviceCalledDirectlyRefusesACallWithoutAValidToken(Service service, Credential credential) {
        assertThatThrownBy(() -> service.client()
                        .get()
                        .uri(service.path)
                        .headers(headers -> {
                            if (credential.bearer != null) {
                                headers.setBearerAuth(credential.bearer);
                            }
                        })
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOfSatisfying(
                        HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
    }

    private static Map<String, Object> eventOf(ActivityStreamSupport.Event event) {
        return Map.of("name", event.name(), "data", event.data());
    }

    private static Map<String, Object> transferData(
            UUID transferId, String senderToken, UUID recipientId, String amount) {
        return Map.of(
                "transferId", transferId.toString(),
                "senderId", claimsOf(senderToken).get("sub"),
                "recipientId", recipientId.toString(),
                "amount", amount,
                "currency", "EUR");
    }

    @Test
    @Tag("M4-S36")
    @DisplayName("A transfer sent through the gateway is seen live")
    void transferSentThroughTheGatewayIsSeenLive() throws Exception {
        String senderToken = tokenFor("ines");
        UUID recipientId = E2ESupport.lookupAccountId(gateway, "marco", senderToken);
        try (ActivityStreamSupport.Stream stream = new ActivityStreamSupport.Stream(
                HttpClient.newHttpClient(), baseUrlOf("api-gateway", GATEWAY_PORT), senderToken)) {
            // A recipient the sender paid before.
            warmUp(stream, senderToken, recipientId);
            BigDecimal balanceBefore = walletBalance(senderToken);

            UUID transferId = send(gateway, senderToken, recipientId, "50.00");

            assertThat(eventOf(stream.take(1).get(0)))
                    .isEqualTo(Map.of(
                            "name",
                            "transfer.posted",
                            "data",
                            transferData(transferId, senderToken, recipientId, "50.0000")));
            assertThat(walletBalance(senderToken))
                    .isEqualByComparingTo(balanceBefore.subtract(new BigDecimal("50.00")));
        }
    }

    // The activity listener reads from the latest offset with a fresh group, so an event published
    // before its partitions are assigned is lost. Reading one small transfer's event off the
    // stream proves the listener is live before the test sends the transfer it is about.
    private static void warmUp(ActivityStreamSupport.Stream stream, String senderToken, UUID recipientId)
            throws Exception {
        UUID warmUp = send(gateway, senderToken, recipientId, "1.00");
        assertThat(eventOf(stream.take(1).get(0)))
                .isEqualTo(Map.of(
                        "name", "transfer.posted", "data", transferData(warmUp, senderToken, recipientId, "1.0000")));
    }

    private static BigDecimal walletBalance(String token) {
        Map<?, ?> wallet = gateway.get()
                .uri("/v1/wallet")
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(Map.class);
        return new BigDecimal((String) ((Map<?, ?>) wallet.get("balance")).get("amount"));
    }

    @Test
    @Tag("M4-S37")
    @DisplayName("A flagged transfer is confirmed through the gateway")
    void flaggedTransferIsConfirmedThroughTheGateway() throws Exception {
        // Nothing else of this class sends from hugo, so marco is a recipient hugo never paid; the
        // warm-up goes to ines so it leaves marco unknown.
        String senderToken = tokenFor("hugo");
        UUID recipientId = E2ESupport.lookupAccountId(gateway, "marco", senderToken);
        UUID warmUpRecipientId = E2ESupport.lookupAccountId(gateway, "ines", senderToken);
        try (ActivityStreamSupport.Stream stream = new ActivityStreamSupport.Stream(
                HttpClient.newHttpClient(), baseUrlOf("api-gateway", GATEWAY_PORT), senderToken)) {
            warmUp(stream, senderToken, warmUpRecipientId);

            UUID transferId = send(gateway, senderToken, recipientId, "500.00");

            Map<String, Object> data = transferData(transferId, senderToken, recipientId, "500.0000");
            assertThat(eventOf(stream.take(1).get(0))).isEqualTo(Map.of("name", "transfer.blocked", "data", data));
            assertThatThrownBy(() -> confirm(gateway, transferId, senderToken, "not-the-code"))
                    .isInstanceOfSatisfying(HttpClientErrorException.class, e -> {
                        assertThat(e.getStatusCode().value()).isEqualTo(422);
                        assertThat(e.getResponseBodyAs(Map.class).get("code")).isEqualTo("INVALID_VERIFICATION_CODE");
                    });
            assertThat(confirm(gateway, transferId, senderToken, VERIFICATION_CODE))
                    .isEqualTo(202);
            assertThat(eventOf(stream.take(1).get(0))).isEqualTo(Map.of("name", "transfer.posted", "data", data));
        }
    }

    @Test
    @DisplayName("A transfer to a recipient named by handle is accepted with the caller's token")
    void transferToARecipientNamedByHandleIsAccepted() {
        // ines pays marco: no flagged-flow test sends from ines, and the fraud history that makes
        // a recipient known is per sender, so this transfer cannot change what those tests see.
        UUID recipientId = E2ESupport.lookupAccountId(ledger, "marco", tokenFor("ines"));

        Map<?, ?> created = orchestrator
                .post()
                .uri("/v1/transfers")
                .headers(headers -> headers.setBearerAuth(tokenFor("ines")))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"toHandle": "@marco", "amount": {"currency": "EUR", "amount": "1.00"}}""")
                .retrieve()
                .body(Map.class);

        assertThat(created.get("recipientId")).isEqualTo(recipientId.toString());
    }

    @Test
    @Tag("M3-S42")
    @DisplayName("A flagged transfer confirmed with the configured code is posted")
    void flaggedTransferConfirmedWithTheConfiguredCodeIsPosted() {
        String senderToken = tokenFor("marco");
        UUID recipientId = E2ESupport.lookupAccountId(ledger, "ines", senderToken);

        UUID transferId = flaggedTransfer(senderToken, recipientId);
        E2ESupport.waitForStatus(orchestrator, transferId, "Bearer " + senderToken, "BLOCKED", WAIT);

        assertThat(confirm(transferId, senderToken)).isEqualTo(202);
        E2ESupport.waitForStatus(orchestrator, transferId, "Bearer " + senderToken, "POSTED", WAIT);
    }

    @Test
    @Tag("M3-S43")
    @DisplayName("A flagged transfer left alone expires")
    void flaggedTransferLeftAloneExpires() {
        String senderToken = tokenFor("marco");
        UUID recipientId = E2ESupport.lookupAccountId(ledger, "hugo", senderToken);

        UUID transferId = flaggedTransfer(senderToken, recipientId);
        Map<?, ?> failed = E2ESupport.waitForStatus(orchestrator, transferId, "Bearer " + senderToken, "FAILED", WAIT);

        assertThat(failed.get("failureReason")).isEqualTo("VERIFICATION_EXPIRED");
        assertThatThrownBy(() -> confirm(transferId, senderToken))
                .isInstanceOfSatisfying(HttpClientErrorException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(410);
                    assertThat(e.getResponseBodyAs(Map.class).get("code")).isEqualTo("VERIFICATION_EXPIRED");
                });
    }
}
