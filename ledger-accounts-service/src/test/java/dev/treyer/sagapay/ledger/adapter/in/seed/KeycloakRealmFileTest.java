package dev.treyer.sagapay.ledger.adapter.in.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Keycloak realm that Compose imports names its users by the ids of the sample accounts the
 * ledger seeds. The two files live in different places, so only this test ties them.
 */
class KeycloakRealmFileTest {

    private static final Path REALM_FILE =
            Paths.get(System.getProperty("user.dir")).getParent().resolve("infra/keycloak/sagapay-realm.json");

    private static final Path ENV_EXAMPLE_FILE =
            Paths.get(System.getProperty("user.dir")).getParent().resolve("infra/keycloak.env.example");

    private static JsonNode realm() {
        return JsonMapper.builder().build().readTree(REALM_FILE);
    }

    @Test
    @DisplayName("Each realm user has the id of the seeded account with its handle")
    void eachRealmUserHasTheIdOfTheSeededAccountWithItsHandle() {
        Map<String, String> idsByUsername = new LinkedHashMap<>();
        realm().get("users")
                .forEach(user -> idsByUsername.put(
                        user.get("username").asString(), user.get("id").asString()));

        assertThat(idsByUsername)
                .isEqualTo(Map.of(
                        "marco", "00000000-0000-4000-a000-000000000013",
                        "ines", "00000000-0000-4000-a000-000000000009",
                        "hugo", "00000000-0000-4000-a000-000000000008"));
    }

    @Test
    @DisplayName("Each realm user's password is a variable that the example env file declares")
    void eachRealmUsersPasswordIsAVariableThatTheExampleEnvFileDeclares() throws IOException {
        List<String> declared = Files.readAllLines(ENV_EXAMPLE_FILE).stream()
                .filter(line -> !line.startsWith("#") && line.contains("="))
                .map(line -> line.substring(0, line.indexOf('=')))
                .toList();

        List<String> passwords = realm().get("users")
                .valueStream()
                .map(user -> user.get("credentials").get(0).get("value").asString())
                .toList();

        assertThat(passwords).hasSize(3).allMatch(value -> value.matches("\\$\\{[A-Z_]+}"));
        assertThat(passwords)
                .extracting(value -> value.substring(2, value.length() - 1))
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    @DisplayName("The realm is named sagapay")
    void theRealmIsNamedSagapay() {
        assertThat(realm().get("realm").asString()).isEqualTo("sagapay");
    }

    @Test
    @DisplayName("The command-line client is public and allows the password grant")
    void theCommandLineClientIsPublicAndAllowsThePasswordGrant() {
        List<JsonNode> clients = realm().get("clients").valueStream().toList();

        assertThat(clients).hasSize(1);
        JsonNode client = clients.get(0);
        assertThat(client.get("clientId").asString()).isEqualTo("sagapay-cli");
        assertThat(client.get("publicClient").asBoolean()).isTrue();
        assertThat(client.get("directAccessGrantsEnabled").asBoolean()).isTrue();
        assertThat(client.get("standardFlowEnabled").asBoolean()).isFalse();
    }
}
