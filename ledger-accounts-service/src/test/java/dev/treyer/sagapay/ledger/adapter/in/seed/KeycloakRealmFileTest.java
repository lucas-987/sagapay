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

    private static JsonNode client(String clientId) {
        return realm().get("clients")
                .valueStream()
                .filter(client -> client.get("clientId").asString().equals(clientId))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> strings(JsonNode array) {
        return array == null
                ? List.of()
                : array.valueStream().map(JsonNode::asString).toList();
    }

    @Test
    @DisplayName("The command-line client is public and allows the password grant")
    void theCommandLineClientIsPublicAndAllowsThePasswordGrant() {
        JsonNode client = client("sagapay-cli");

        assertThat(client.get("publicClient").asBoolean()).isTrue();
        assertThat(client.get("directAccessGrantsEnabled").asBoolean()).isTrue();
        assertThat(client.get("standardFlowEnabled").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("The command-line client maps the subject itself, whichever scopes the realm defines")
    void theCommandLineClientMapsTheSubjectItself() {
        assertThat(client("sagapay-cli")
                        .get("protocolMappers")
                        .valueStream()
                        .map(mapper -> mapper.get("protocolMapper").asString()))
                .containsExactly("oidc-sub-mapper");
    }

    @Test
    @DisplayName("The realm has the command-line client and the orchestrator's client, nothing else")
    void theRealmHasTheCommandLineClientAndTheOrchestratorsClientNothingElse() {
        assertThat(realm().get("clients")
                        .valueStream()
                        .map(client -> client.get("clientId").asString()))
                .containsExactlyInAnyOrder("sagapay-cli", "transfer-orchestrator");
    }

    @Test
    @DisplayName(
            "The orchestrator's client is confidential, service-account only, with its secret read from the environment")
    void theOrchestratorsClientIsConfidentialServiceAccountOnly() {
        JsonNode client = client("transfer-orchestrator");

        assertThat(client.get("publicClient").asBoolean()).isFalse();
        assertThat(client.get("clientAuthenticatorType").asString()).isEqualTo("client-secret");
        assertThat(client.get("secret").asString()).isEqualTo("${ORCHESTRATOR_CLIENT_SECRET}");
        assertThat(client.get("serviceAccountsEnabled").asBoolean()).isTrue();
        assertThat(client.get("standardFlowEnabled").asBoolean()).isFalse();
        assertThat(client.get("implicitFlowEnabled").asBoolean()).isFalse();
        assertThat(client.get("directAccessGrantsEnabled").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("The orchestrator's access token lives five minutes")
    void theOrchestratorsAccessTokenLivesFiveMinutes() {
        assertThat(client("transfer-orchestrator")
                        .get("attributes")
                        .get("access.token.lifespan")
                        .asString())
                .isEqualTo("300");
    }

    @Test
    @DisplayName("The ledger:write scope names the ledger as audience and shows in the token's scope")
    void theLedgerWriteScopeNamesTheLedgerAsAudienceAndShowsInTheTokensScope() {
        List<JsonNode> scopes = realm().get("clientScopes").valueStream().toList();
        assertThat(scopes).hasSize(1);
        JsonNode scope = scopes.get(0);
        assertThat(scope.get("name").asString()).isEqualTo("ledger:write");
        assertThat(scope.get("protocol").asString()).isEqualTo("openid-connect");
        assertThat(scope.get("attributes").get("include.in.token.scope").asString())
                .isEqualTo("true");

        List<JsonNode> mappers = scope.get("protocolMappers").valueStream().toList();
        assertThat(mappers).hasSize(1);
        JsonNode config = mappers.get(0).get("config");
        assertThat(mappers.get(0).get("protocolMapper").asString()).isEqualTo("oidc-audience-mapper");
        assertThat(config.get("included.custom.audience").asString()).isEqualTo("ledger-accounts-service");
        assertThat(config.get("access.token.claim").asString()).isEqualTo("true");
    }

    @Test
    @DisplayName("The ledger:write scope is assigned to the orchestrator's client and to no other client")
    void theLedgerWriteScopeIsAssignedToTheOrchestratorsClientOnly() {
        assertThat(strings(client("transfer-orchestrator").get("defaultClientScopes")))
                .containsExactly("ledger:write");
        assertThat(strings(client("transfer-orchestrator").get("optionalClientScopes")))
                .isEmpty();
        assertThat(strings(client("sagapay-cli").get("defaultClientScopes"))).doesNotContain("ledger:write");
        assertThat(strings(client("sagapay-cli").get("optionalClientScopes"))).doesNotContain("ledger:write");
        assertThat(strings(realm().get("defaultDefaultClientScopes"))).doesNotContain("ledger:write");
        assertThat(strings(realm().get("optionalDefaultClientScopes"))).doesNotContain("ledger:write");
    }
}
