package dev.treyer.sagapay.gateway;

import org.assertj.core.api.InstanceOfAssertFactories;
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
 * File lint of the compose entry and of the image: it proves the declared shape, not that the
 * stack starts and the gateway turns healthy.
 */
class ComposeGatewayTest {

    private static final Path REPOSITORY_ROOT =
            Paths.get(System.getProperty("user.dir")).getParent();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services() throws IOException {
        try (Reader reader = Files.newBufferedReader(REPOSITORY_ROOT.resolve("infra/docker-compose.yml"))) {
            Map<String, Object> compose = new Yaml().load(reader);
            return (Map<String, Object>) compose.get("services");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> gateway() throws IOException {
        return (Map<String, Object>) services().get("api-gateway");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> environmentOf(String service) throws IOException {
        return (Map<String, Object>) ((Map<String, Object>) services().get(service)).get("environment");
    }

    @Test
    void theGatewayIsPublishedOnPort8080ByDefaultWithAnOverridableHostSide() throws IOException {
        assertThat(gateway().get("ports")).isEqualTo(List.of("${GATEWAY_HOST_PORT:-8080}:8080"));
    }

    @Test
    void theGatewayHasNoStoreOfItsOwn() throws IOException {
        assertThat(gateway()).doesNotContainKey("volumes");
        assertThat(services().keySet()).noneMatch(name -> name.contains("gateway") && !name.equals("api-gateway"));
        assertThat(gateway().get("depends_on"))
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("keycloak", "transfer-orchestrator", "ledger-accounts-service");
    }

    @Test
    void theGatewayChecksTokensAgainstTheRealmOfTheServices() throws IOException {
        Map<String, Object> environment = environmentOf("api-gateway");

        assertThat(environment.get("OAUTH2_ISSUER_URI"))
                .isEqualTo(environmentOf("transfer-orchestrator").get("OAUTH2_ISSUER_URI"))
                .isEqualTo(environmentOf("ledger-accounts-service").get("OAUTH2_ISSUER_URI"));
        assertThat(environment.get("OAUTH2_JWK_SET_URI"))
                .isEqualTo(environmentOf("transfer-orchestrator").get("OAUTH2_JWK_SET_URI"))
                .isEqualTo(environmentOf("ledger-accounts-service").get("OAUTH2_JWK_SET_URI"));
    }

    @Test
    void theGatewayRoutesToTheServicesOfTheNetwork() throws IOException {
        assertThat(environmentOf("api-gateway"))
                .containsEntry("ORCHESTRATOR_URI", "http://transfer-orchestrator:8082")
                .containsEntry("LEDGER_URI", "http://ledger-accounts-service:8081");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theGatewayIsHealthyWhenItsOpenHealthEndpointAnswers() throws IOException {
        Map<String, Object> healthcheck = (Map<String, Object>) gateway().get("healthcheck");

        assertThat(healthcheck.get("test").toString()).contains("http://localhost:8080/actuator/health");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theGatewayImageIsBuiltFromItsOwnDockerfileWithTheMavenMirrorArgument() throws IOException {
        Map<String, Object> build = (Map<String, Object>) gateway().get("build");
        String dockerfile =
                Files.readString(REPOSITORY_ROOT.resolve(build.get("dockerfile").toString()));

        assertThat(build.get("dockerfile")).isEqualTo("api-gateway/Dockerfile");
        assertThat((Map<String, Object>) build.get("args")).containsKey("MAVEN_MIRROR_URL");
        assertThat(dockerfile).contains("ARG MAVEN_MIRROR_URL=").contains("EXPOSE 8080");
    }
}
