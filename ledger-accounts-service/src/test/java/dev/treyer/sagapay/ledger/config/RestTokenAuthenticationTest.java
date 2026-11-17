package dev.treyer.sagapay.ledger.config;

import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import dev.treyer.sagapay.ledger.application.port.out.AccountPort;
import dev.treyer.sagapay.ledger.domain.Account;
import dev.treyer.sagapay.ledger.security.KeySetServer;
import dev.treyer.sagapay.ledger.security.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST accepts the realm's tokens and only those, and answers each with the token owner's own
 * data. The key set comes from a local server, so that what is checked is the decoder Spring
 * Boot builds from the settings, not a mock of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.grpc.server.port=0")
class RestTokenAuthenticationTest {

    private static final KeySetServer IDENTITY_PROVIDER = new KeySetServer();

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

    private HttpResponse<String> get(String pathAndQuery, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String tokenOf(UUID account) {
        return TestTokens.token().subject(account.toString()).build();
    }

    @Test
    void aTokenOfTheRealmReachesTheUseCase() throws Exception {
        // 404, not 401: the request went past authentication to the unknown account.
        assertThat(get("/v1/wallet", tokenOf(UUID.randomUUID())).statusCode()).isEqualTo(404);
    }

    static Stream<Arguments> refusedCalls() {
        String account = UUID.randomUUID().toString();
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("no token", null);
        tokens.put(
                "an expired token",
                TestTokens.token().subject(account).expired().build());
        tokens.put(
                "a token of another issuer",
                TestTokens.token()
                        .subject(account)
                        .issuer("https://other.test/realms/other")
                        .build());
        tokens.put(
                "a token signed by another key",
                TestTokens.token().subject(account).signedByAnotherKey().build());
        tokens.put(
                "a token whose key id the realm does not publish",
                TestTokens.token().subject(account).keyId("unknown-key").build());
        tokens.put("a malformed token", "not-a-token");
        return Stream.of("/v1/wallet", "/v1/wallet/postings", "/v1/users/lookup?handle=nobody")
                .flatMap(path ->
                        tokens.entrySet().stream().map(token -> Arguments.of(path, token.getKey(), token.getValue())));
    }

    @ParameterizedTest(name = "{0} refuses {1}")
    @MethodSource("refusedCalls")
    void aCallWithoutAValidTokenIsRefused(String path, String kind, String token) throws Exception {
        HttpResponse<String> response = get(path, token);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .hasValueSatisfying(h -> assertThat(h).startsWith("Bearer"));
    }

    @Test
    void aSignedInUserLooksAHandleUp() throws Exception {
        Account account = accounts.create(
                new Account(UUID.randomUUID(), "found-" + UUID.randomUUID(), "Found", "EUR", new BigDecimal("1.00")));

        HttpResponse<String> response =
                get("/v1/users/lookup?handle=" + account.getHandle(), tokenOf(UUID.randomUUID()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonMapper.builder()
                        .build()
                        .readTree(response.body())
                        .get("accountId")
                        .asString())
                .isEqualTo(account.getId().toString());
    }

    static Stream<String> subjectsThatAreNotAnAccount() {
        return Stream.of("service-account-test", "", "not-a-uuid", null);
    }

    @ParameterizedTest(name = "a subject of \"{0}\" is refused")
    @MethodSource("subjectsThatAreNotAnAccount")
    void aSubjectThatIsNotAnAccountIdIsRefused(String subject) throws Exception {
        String token = TestTokens.token().subject(subject).build();

        assertThat(get("/v1/wallet", token).statusCode()).isEqualTo(401);
    }

    @Test
    @Tag("M4-S04")
    @DisplayName("A wallet read always returns the caller's own wallet")
    void aWalletReadAlwaysReturnsTheCallersOwnWallet() throws Exception {
        Account user = accounts.create(
                new Account(UUID.randomUUID(), "own-" + UUID.randomUUID(), "Own", "EUR", new BigDecimal("100.00")));
        Account other = accounts.create(
                new Account(UUID.randomUUID(), "other-" + UUID.randomUUID(), "Other", "EUR", new BigDecimal("900.00")));

        HttpResponse<String> response = get("/v1/wallet?accountId=" + other.getId(), tokenOf(user.getId()));

        assertThat(response.statusCode()).isEqualTo(200);
        var wallet = JsonMapper.builder().build().readTree(response.body());
        assertThat(wallet.get("accountId").asString()).isEqualTo(user.getId().toString());
        assertThat(wallet.get("balance").get("amount").asString()).isEqualTo("100.0000");
    }

    @Test
    void postingsOfAnotherAccountAreNotReadableByNamingIt() throws Exception {
        Account user = accounts.create(
                new Account(UUID.randomUUID(), "own-" + UUID.randomUUID(), "Own", "EUR", new BigDecimal("1.00")));

        HttpResponse<String> response =
                get("/v1/wallet/postings?accountId=" + UUID.randomUUID(), tokenOf(user.getId()));

        // The unknown account named in the query would answer 404: the caller's own, empty, answers 200.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonMapper.builder().build().readTree(response.body()).get("items"))
                .isEmpty();
    }

    @ParameterizedTest(name = "{0} is open without a token")
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void healthIsOpenWithoutAToken(String path) throws Exception {
        assertThat(get(path, null).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0} is refused to everyone")
    @ValueSource(
            strings = {"/actuator", "/actuator/prometheus", "/actuator/metrics", "/actuator/info", "/anything-else"})
    void everyPathOutsideTheApiAndHealthIsRefused(String path) throws Exception {
        assertThat(get(path, null).statusCode()).isEqualTo(401);
        assertThat(get(path, tokenOf(UUID.randomUUID())).statusCode()).isEqualTo(403);
    }
}
