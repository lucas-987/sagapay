package dev.treyer.sagapay.orchestrator.config;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Same pattern as the ledger's {@code NoAuthSecurityConfigTest} (ADR 0004),
 * replicated here per the M2 checklist §8.5. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local-noauth")
@Import(TestcontainersConfiguration.class)
class NoAuthSecurityConfigTest {

    @LocalServerPort
    private int port;

    @Test
    void restEndpointIsReachableWithoutAuthenticationUnderLocalNoauthProfile() throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + port + "/v1/transfers/" + UUID.randomUUID()))
                        .GET().build(),
                HttpResponse.BodyHandlers.discarding());

        // 404, not 401: proves the request actually reached the use case (the
        // transfer id is simply unknown) instead of being blocked upstream.
        assertThat(response.statusCode()).isEqualTo(404);
    }
}
