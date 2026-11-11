package dev.treyer.sagapay.ledger.config;

import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import dev.treyer.sagapay.ledger.security.KeySetServer;
import dev.treyer.sagapay.ledger.security.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST accepts the realm's tokens and only those. The key set comes from a local server, so that
 * what is checked is the decoder Spring Boot builds from the settings, not a mock of it.
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

    private int statusWith(String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/wallet?accountId=" + UUID.randomUUID()))
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    @Test
    void aTokenOfTheRealmReachesTheUseCase() throws Exception {
        // 404, not 401: the request went past authentication to the unknown account.
        assertThat(statusWith(TestTokens.token().build())).isEqualTo(404);
    }

    static Stream<String> refusedTokens() {
        return Stream.of(
                TestTokens.token().expired().build(),
                TestTokens.token().issuer("https://other.test/realms/other").build(),
                TestTokens.token().signedByAnotherKey().build(),
                "not-a-token");
    }

    @ParameterizedTest
    @MethodSource("refusedTokens")
    void anyOtherTokenIsRefused(String token) throws Exception {
        assertThat(statusWith(token)).isEqualTo(401);
    }
}
