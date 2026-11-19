package dev.treyer.sagapay.ledger.adapter.in.rest;

import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import dev.treyer.sagapay.ledger.application.port.out.AccountPort;
import dev.treyer.sagapay.ledger.domain.Account;
import dev.treyer.sagapay.ledger.security.KeySetServer;
import dev.treyer.sagapay.ledger.security.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The names read goes through the real servlet chain, use case and query, against PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.grpc.server.port=0")
class UserNamesTest {

    private static final KeySetServer IDENTITY_PROVIDER = new KeySetServer();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void realm(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokens.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IDENTITY_PROVIDER::url);
    }

    @AfterAll
    static void stopIdentityProvider() {
        IDENTITY_PROVIDER.stop();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private AccountPort accounts;

    @Autowired
    private JdbcTemplate jdbc;

    private HttpResponse<String> names(List<String> ids) throws Exception {
        String query = String.join("&", ids.stream().map(id -> "ids=" + id).toList());
        String token = TestTokens.token().subject(UUID.randomUUID().toString()).build();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/users/names?" + query))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Account account(String displayName) {
        return accounts.create(
                new Account(UUID.randomUUID(), "n-" + UUID.randomUUID(), displayName, "EUR", new BigDecimal("7.00")));
    }

    private static Map<String, String> namesByAccountId(HttpResponse<String> response) {
        Map<String, String> byId = new TreeMap<>();
        for (JsonNode item : JSON.readTree(response.body()).get("items")) {
            byId.put(item.get("accountId").asString(), item.get("displayName").asString());
        }
        return byId;
    }

    @Test
    void knownIdsAreNamedAndTheUnknownOneIsLeftOut() throws Exception {
        Account alice = account("Alice Martin");
        Account bob = account("Bob Durand");

        HttpResponse<String> response = names(List.of(
                alice.getId().toString(),
                UUID.randomUUID().toString(),
                bob.getId().toString()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(namesByAccountId(response))
                .isEqualTo(new TreeMap<>(Map.of(
                        alice.getId().toString(), "Alice Martin", bob.getId().toString(), "Bob Durand")));
    }

    @Test
    void anItemCarriesNothingButTheAccountIdAndTheDisplayName() throws Exception {
        Account alice = account("Alice Martin");

        HttpResponse<String> response = names(List.of(alice.getId().toString()));

        JsonNode body = JSON.readTree(response.body());
        assertThat(body.propertyNames()).containsExactly("items");
        Set<String> itemFields = new HashSet<>();
        body.get("items").get(0).propertyNames().forEach(itemFields::add);
        assertThat(itemFields).containsExactlyInAnyOrder("accountId", "displayName");
    }

    @Test
    void aRepeatedIdIsAnsweredOnce() throws Exception {
        Account alice = account("Alice Martin");
        String id = alice.getId().toString();

        HttpResponse<String> response = names(List.of(id, id, id));

        assertThat(JSON.readTree(response.body()).get("items")).hasSize(1);
        assertThat(namesByAccountId(response)).isEqualTo(Map.of(id, "Alice Martin"));
    }

    @Test
    void aRenamedAccountAnswersItsCurrentName() throws Exception {
        Account alice = account("Alice Martin");
        String id = alice.getId().toString();
        assertThat(namesByAccountId(names(List.of(id)))).isEqualTo(Map.of(id, "Alice Martin"));

        jdbc.update("update accounts set display_name = ? where id = ?", "Alice Dupont", alice.getId());

        assertThat(namesByAccountId(names(List.of(id)))).isEqualTo(Map.of(id, "Alice Dupont"));
    }

    @Test
    void exactlyFiftyIdsAreAnswered() throws Exception {
        Account alice = account("Alice Martin");
        List<String> ids = new ArrayList<>(List.of(alice.getId().toString()));
        Stream.generate(UUID::randomUUID).limit(49).map(UUID::toString).forEach(ids::add);

        HttpResponse<String> response = names(ids);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(namesByAccountId(response)).isEqualTo(Map.of(alice.getId().toString(), "Alice Martin"));
    }

    @Test
    void fiftyOneIdsAreRefused() throws Exception {
        List<String> ids =
                Stream.generate(UUID::randomUUID).limit(51).map(UUID::toString).toList();

        HttpResponse<String> response = names(ids);

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode problem = JSON.readTree(response.body());
        assertThat(problem.get("code").asString()).isEqualTo("INVALID_ACCOUNT_IDS");
        assertThat(problem.get("detail").asString()).isEqualTo("ids must hold between 1 and 50 account ids");
    }

    @Test
    void idsThatAreNotKnownAreAnsweredWithAnEmptyList() throws Exception {
        HttpResponse<String> response =
                names(List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).toString()).isEqualTo("{\"items\":[]}");
    }
}
