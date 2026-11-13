package dev.treyer.sagapay.ledger.adapter.in.grpc;

import dev.treyer.sagapay.common.v1.Money;
import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import dev.treyer.sagapay.ledger.adapter.out.persistence.AccountRepository;
import dev.treyer.sagapay.ledger.domain.Account;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveRequest;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveResponse;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The realm file as Compose imports it, in a real Keycloak: the orchestrator's client gets a token
 * the ledger admits, a user's token is refused, and the ledger keeps admitting a valid token while
 * the identity provider is down. Heavy (a Keycloak container): not part of a quick loop. Ordered
 * because the last test stops Keycloak for good.
 */
@SpringBootTest
@AutoConfigureTestGrpcTransport
@Import(TestcontainersConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GrpcServiceTokenKeycloakTest {

    private static final String RECIPIENT = UUID.randomUUID().toString();

    private static final String CLIENT_SECRET = "keycloak-test-client-secret";
    private static final String USER_PASSWORD = "keycloak-test-user-password";
    private static final Path REALM_FILE =
            Paths.get(System.getProperty("user.dir")).getParent().resolve("infra/keycloak/sagapay-realm.json");

    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.4")
            .withCommand("start-dev", "--import-realm")
            .withEnv(Map.of(
                    "KC_BOOTSTRAP_ADMIN_USERNAME", "admin",
                    "KC_BOOTSTRAP_ADMIN_PASSWORD", "keycloak-test-admin-password",
                    "ORCHESTRATOR_CLIENT_SECRET", CLIENT_SECRET,
                    "MARCO_PASSWORD", USER_PASSWORD,
                    "INES_PASSWORD", USER_PASSWORD,
                    "HUGO_PASSWORD", USER_PASSWORD))
            .withCopyFileToContainer(
                    MountableFile.forHostPath(REALM_FILE), "/opt/keycloak/data/import/sagapay-realm.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/sagapay").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)));

    static {
        KEYCLOAK.start();
    }

    /** Tokens carry the address they were fetched from as issuer. */
    private static String realmUrl() {
        return "http://%s:%d/realms/sagapay".formatted(KEYCLOAK.getHost(), KEYCLOAK.getMappedPort(8080));
    }

    @DynamicPropertySource
    static void realm(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", GrpcServiceTokenKeycloakTest::realmUrl);
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> realmUrl() + "/protocol/openid-connect/certs");
    }

    @Autowired
    private GrpcChannelFactory channelFactory;

    @Autowired
    private AccountRepository accounts;

    private static String tokenWith(MultiValueMap<String, String> form) {
        Map<?, ?> answer = RestClient.create()
                .post()
                .uri(realmUrl() + "/protocol/openid-connect/token")
                .body(form)
                .retrieve()
                .body(Map.class);
        return (String) answer.get("access_token");
    }

    private static String orchestratorToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "transfer-orchestrator");
        form.add("client_secret", CLIENT_SECRET);
        return tokenWith(form);
    }

    private static String userToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "sagapay-cli");
        form.add("username", "marco");
        form.add("password", USER_PASSWORD);
        return tokenWith(form);
    }

    private CheckAndReserveResponse reserve(String token) {
        UUID accountId = accounts.save(
                        new Account("test-" + UUID.randomUUID(), "Test User", "EUR", new BigDecimal("100.0000")))
                .getId();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return LedgerServiceGrpc.newBlockingStub(channelFactory.createChannel("test"))
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .checkAndReserve(CheckAndReserveRequest.newBuilder()
                        .setTransferId(UUID.randomUUID().toString())
                        .setFromAccountId(accountId.toString())
                        .setToAccountId(RECIPIENT)
                        .setAmount(Money.newBuilder()
                                .setCurrency("EUR")
                                .setAmount("10.00")
                                .build())
                        .build());
    }

    @Test
    @Order(1)
    void theOrchestratorsClientObtainsATokenTheLedgerAccepts() {
        assertThat(reserve(orchestratorToken()).getStatus()).isEqualTo(CheckAndReserveResponse.Status.OK);
    }

    @Test
    @Order(2)
    void aUserTokenOfTheSameRealmIsRefusedWithPermissionDenied() {
        assertThatThrownBy(() -> reserve(userToken()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    @Test
    @Order(3)
    void theLedgerKeepsAcceptingAValidTokenWhileKeycloakIsDown() {
        String token = orchestratorToken();
        reserve(token);

        KEYCLOAK.stop();

        assertThat(reserve(token).getStatus()).isEqualTo(CheckAndReserveResponse.Status.OK);
    }
}
