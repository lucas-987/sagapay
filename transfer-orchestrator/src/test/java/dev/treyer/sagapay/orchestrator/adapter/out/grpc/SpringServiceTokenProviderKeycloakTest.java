package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.treyer.sagapay.orchestrator.config.LedgerAuthProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provider against the realm file as Compose imports it, in a real Keycloak: the token it
 * gets names the ledger as audience and carries the ledger scope, and a wrong secret is an
 * unavailable token. Heavy (a Keycloak container): not part of a quick loop.
 */
class SpringServiceTokenProviderKeycloakTest {

    private static final String CLIENT_SECRET = "keycloak-test-client-secret";
    private static final Path REALM_FILE =
            Paths.get(System.getProperty("user.dir")).getParent().resolve("infra/keycloak/sagapay-realm.json");

    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.4")
            .withCommand("start-dev", "--import-realm")
            .withEnv(Map.of(
                    "KC_BOOTSTRAP_ADMIN_USERNAME", "admin",
                    "KC_BOOTSTRAP_ADMIN_PASSWORD", "keycloak-test-admin-password",
                    "ORCHESTRATOR_CLIENT_SECRET", CLIENT_SECRET,
                    "MARCO_PASSWORD", "unused",
                    "INES_PASSWORD", "unused",
                    "HUGO_PASSWORD", "unused"))
            .withCopyFileToContainer(
                    MountableFile.forHostPath(REALM_FILE), "/opt/keycloak/data/import/sagapay-realm.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/sagapay").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)));

    @BeforeAll
    static void startKeycloak() {
        KEYCLOAK.start();
    }

    @AfterAll
    static void stopKeycloak() {
        KEYCLOAK.stop();
    }

    private static String realmUrl() {
        return "http://%s:%d/realms/sagapay".formatted(KEYCLOAK.getHost(), KEYCLOAK.getMappedPort(8080));
    }

    private static SpringServiceTokenProvider provider(String secret) {
        return new SpringServiceTokenProvider(
                new LedgerAuthProperties(
                        "transfer-orchestrator",
                        secret,
                        URI.create(realmUrl() + "/protocol/openid-connect/token"),
                        30_000,
                        5000),
                Clock.systemUTC());
    }

    @Test
    void theTokenNamesTheLedgerAsAudienceAndCarriesTheLedgerScope() throws ParseException {
        JWTClaimsSet claims = SignedJWT.parse(provider(CLIENT_SECRET).token()).getJWTClaimsSet();

        assertThat(claims.getIssuer()).isEqualTo(realmUrl());
        assertThat(claims.getAudience()).containsExactly("ledger-accounts-service");
        assertThat(claims.getStringClaim("scope")).isEqualTo("ledger:write");
    }

    @Test
    void theTokenExpiresFiveMinutesAfterItIsIssued() throws ParseException {
        JWTClaimsSet claims = SignedJWT.parse(provider(CLIENT_SECRET).token()).getJWTClaimsSet();

        assertThat(Duration.between(
                        claims.getIssueTime().toInstant(),
                        claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aWrongSecretIsAnUnavailableToken() {
        assertThatThrownBy(() -> provider("not-the-secret").token())
                .isInstanceOf(ServiceTokenUnavailableException.class)
                .hasMessage("token endpoint answered HTTP 401");
    }
}
