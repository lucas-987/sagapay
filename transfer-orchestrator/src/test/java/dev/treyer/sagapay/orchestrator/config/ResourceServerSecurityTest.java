package dev.treyer.sagapay.orchestrator.config;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.security.TestJwtConfiguration;
import dev.treyer.sagapay.orchestrator.security.TestTokens;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
class ResourceServerSecurityTest {

    private static final UUID OWNER = UUID.fromString("0a1b2c3d-0000-4000-8000-0000000000a1");
    private static final UUID OTHER_USER = UUID.fromString("0a1b2c3d-0000-4000-8000-0000000000b2");
    private static final UUID RECIPIENT = UUID.fromString("0a1b2c3d-0000-4000-8000-0000000000c3");
    private static final String BODY = """
            {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "12.00"}}""".formatted(RECIPIENT);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private LedgerPort ledger;

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> createTransfer(String token, String claimedUserId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/transfers"))
                .POST(HttpRequest.BodyPublishers.ofString(BODY))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (claimedUserId != null) {
            request.header("X-User-Id", claimedUserId);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int transferCount() {
        return jdbc.queryForObject("select count(*) from transfers", Integer.class);
    }

    @Test
    @Tag("M4-S03")
    @DisplayName("A transfer is sent by the owner of the token, whatever the request claims")
    void aTransferIsSentByTheOwnerOfTheTokenWhateverTheRequestClaims() throws Exception {
        when(ledger.checkAndReserve(any(), any(), any(Money.class)))
                .thenThrow(new LedgerUnavailableException(new RuntimeException("down")));

        HttpResponse<String> response = createTransfer(TestTokens.forSubject(OWNER), OTHER_USER.toString());

        assertThat(response.statusCode()).isEqualTo(202);
        // id and timestamps are generated: only the sender of the answer is compared
        assertThat(JSON.readValue(response.body(), Map.class).get("senderId")).isEqualTo(OWNER.toString());
        List<String> senders = jdbc.queryForList(
                "select sender_id::text from transfers where recipient_id = ?", String.class, RECIPIENT);
        assertThat(senders).containsExactly(OWNER.toString());
        verify(ledger, timeout(5000)).checkAndReserve(any(), eq(OWNER), any(Money.class));
        verify(ledger, never()).checkAndReserve(any(), eq(OTHER_USER), any(Money.class));
    }

    private enum BadToken {
        NONE(owner -> null, "Bearer"),
        EXPIRED(TestTokens::expiredFor, "Bearer error=\"invalid_token\""),
        BAD_SIGNATURE(TestTokens::signedByAnotherKey, "Bearer error=\"invalid_token\""),
        WRONG_ISSUER(TestTokens::fromAnotherIssuer, "Bearer error=\"invalid_token\"");

        private final Function<UUID, String> token;
        private final String challengePrefix;

        BadToken(Function<UUID, String> token, String challengePrefix) {
            this.token = token;
            this.challengePrefix = challengePrefix;
        }
    }

    private record Endpoint(String method, String path, String body) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private static final UUID SOME_TRANSFER = UUID.fromString("0a1b2c3d-0000-4000-8000-0000000000d4");

    private static final List<Endpoint> USER_ENDPOINTS = List.of(
            new Endpoint("GET", "/v1/transfers", null),
            new Endpoint("GET", "/v1/transfers/" + SOME_TRANSFER, null),
            new Endpoint("POST", "/v1/transfers/" + SOME_TRANSFER + "/confirm", "{\"verificationToken\": \"x\"}"),
            new Endpoint("POST", "/v1/transfers", BODY),
            new Endpoint("POST", "/v1/requests", "{}"));

    static Stream<Arguments> everyEndpointWithEveryBadToken() {
        return USER_ENDPOINTS.stream()
                .flatMap(endpoint -> Stream.of(BadToken.values()).map(bad -> Arguments.of(endpoint, bad)));
    }

    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("everyEndpointWithEveryBadToken")
    void everyUserEndpointAnswers401WithoutAValidToken(Endpoint endpoint, BadToken bad) throws Exception {
        int before = transferCount();

        HttpResponse<String> response =
                send(endpoint.method(), endpoint.path(), bad.token.apply(OWNER), endpoint.body());

        // The challenge of an invalid token carries a description with the cause (and a time for an
        // expired one): only its start is fixed.
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow())
                .startsWith(bad.challengePrefix);
        assertThat(response.body()).isEmpty();
        assertThat(transferCount()).isEqualTo(before);
        verify(ledger, never()).checkAndReserve(any(), any(), any(Money.class));
    }

    @Test
    void aTokenWhoseSubjectIsNotAnAccountIdAnswers401AndCreatesNothing() throws Exception {
        int before = transferCount();

        HttpResponse<String> response = createTransfer(TestTokens.forSubject("not-a-uuid"), null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(transferCount()).isEqualTo(before);
        verify(ledger, never()).checkAndReserve(any(), any(), any(Money.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void healthAnswers200WithoutAToken(String path) throws Exception {
        assertThat(send("GET", path, null, null).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/prometheus", "/actuator/metrics", "/unknown", "/error"})
    void everyPathOutsideTheTransferApiAndHealthIsRefusedWithoutAToken(String path) throws Exception {
        assertThat(send("GET", path, null, null).statusCode()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/prometheus", "/actuator/metrics", "/unknown", "/error"})
    void everyPathOutsideTheTransferApiAndHealthIsRefusedToASignedInUser(String path) throws Exception {
        assertThat(send("GET", path, TestTokens.forSubject(OWNER), null).statusCode())
                .isEqualTo(403);
    }

    @Test
    void aSignedInUserSeesTheRealStatusOfARequestSpringRejectsBeforeTheController() throws Exception {
        HttpResponse<String> response = send("POST", "/v1/transfers", TestTokens.forSubject(OWNER), "{not json");

        assertThat(response.statusCode()).isEqualTo(400);
    }
}
