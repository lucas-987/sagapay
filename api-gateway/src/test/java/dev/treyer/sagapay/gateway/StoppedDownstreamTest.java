package dev.treyer.sagapay.gateway;

import dev.treyer.sagapay.gateway.support.TestIdentityProvider;
import dev.treyer.sagapay.gateway.support.TestTokens;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoppedDownstreamTest {

    private static final String NOBODY_LISTENS = "http://localhost:" + unusedPort();

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("ORCHESTRATOR_URI", () -> NOBODY_LISTENS);
        registry.add("LEDGER_URI", () -> NOBODY_LISTENS);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokens.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestIdentityProvider::jwkSetUri);
    }

    @LocalServerPort
    private int port;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/v1/transfers", "/v1/wallet"})
    void aStoppedServiceIsAnsweredWithAServerErrorAndNotAHang(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + TestTokens.valid())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isBetween(500, 599);
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
