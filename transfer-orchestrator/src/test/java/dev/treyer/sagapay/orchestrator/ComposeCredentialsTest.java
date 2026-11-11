package dev.treyer.sagapay.orchestrator;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * File lint of the compose file and of {@code .env.example}: the credential reaches the services
 * that share it, with no fallback, and the ledger's gRPC port stays inside the network. It does not
 * prove that a service refuses to start without the credential (see {@code LedgerAuthPropertiesTest}).
 */
class ComposeCredentialsTest {

    private static final Path COMPOSE_FILE =
            Paths.get(System.getProperty("user.dir")).getParent().resolve("infra/docker-compose.yml");

    private static final Path ENV_EXAMPLE_FILE =
            Paths.get(System.getProperty("user.dir")).getParent().resolve(".env.example");

    private static final String SECRET_REQUIRED =
            "${ORCHESTRATOR_CLIENT_SECRET:?set ORCHESTRATOR_CLIENT_SECRET in .env}";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> serviceOf(String service) throws IOException {
        try (Reader reader = Files.newBufferedReader(COMPOSE_FILE)) {
            Map<String, Object> compose = new Yaml().load(reader);
            return (Map<String, Object>) ((Map<String, Object>) compose.get("services")).get(service);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> environmentOf(String service) throws IOException {
        try (Reader reader = Files.newBufferedReader(COMPOSE_FILE)) {
            Map<String, Object> compose = new Yaml().load(reader);
            Map<String, Object> services = (Map<String, Object>) compose.get("services");
            return (Map<String, Object>) ((Map<String, Object>) services.get(service)).get("environment");
        }
    }

    @Test
    void theOrchestratorGetsItsClientIdAndSecretWithNoFallback() throws IOException {
        Map<String, Object> environment = environmentOf("transfer-orchestrator");

        assertThat(environment)
                .containsEntry("ORCHESTRATOR_CLIENT_ID", "${ORCHESTRATOR_CLIENT_ID}")
                .containsEntry("ORCHESTRATOR_CLIENT_SECRET", SECRET_REQUIRED)
                .containsEntry("OAUTH2_TOKEN_URI", "http://keycloak:8180/realms/sagapay/protocol/openid-connect/token");
    }

    @Test
    void keycloakImportsTheSameSecretIntoTheRealm() throws IOException {
        assertThat(environmentOf("keycloak")).containsEntry("ORCHESTRATOR_CLIENT_SECRET", SECRET_REQUIRED);
    }

    @Test
    void theLedgerChecksTokensAgainstTheRealmOfTheOrchestrator() throws IOException {
        Map<String, Object> ledger = environmentOf("ledger-accounts-service");
        Map<String, Object> orchestrator = environmentOf("transfer-orchestrator");

        assertThat(ledger)
                .containsEntry("OAUTH2_ISSUER_URI", "http://keycloak:8180/realms/sagapay")
                .containsEntry(
                        "OAUTH2_JWK_SET_URI", "http://keycloak:8180/realms/sagapay/protocol/openid-connect/certs");
        assertThat(ledger.get("OAUTH2_ISSUER_URI")).isEqualTo(orchestrator.get("OAUTH2_ISSUER_URI"));
        assertThat(ledger.get("OAUTH2_JWK_SET_URI")).isEqualTo(orchestrator.get("OAUTH2_JWK_SET_URI"));
    }

    @Test
    void theLedgerKeepsLocalNoauthInCompose() throws IOException {
        assertThat(environmentOf("ledger-accounts-service"))
                .containsEntry("SPRING_PROFILES_ACTIVE", "local,local-noauth");
    }

    @Test
    void theLedgerPublishesItsRestPortAndNotItsGrpcPort() throws IOException {
        assertThat(serviceOf("ledger-accounts-service").get("ports")).isEqualTo(List.of("8081:8081"));
    }

    @Test
    void theEnvExampleDeclaresTheOrchestratorsClientIdAndAnEmptyClientSecret() throws IOException {
        assertThat(Files.readAllLines(ENV_EXAMPLE_FILE))
                .contains("ORCHESTRATOR_CLIENT_ID=transfer-orchestrator", "ORCHESTRATOR_CLIENT_SECRET=");
    }
}
