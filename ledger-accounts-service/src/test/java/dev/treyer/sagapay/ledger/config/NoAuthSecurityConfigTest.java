package dev.treyer.sagapay.ledger.config;

import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The JDK's {@code HttpClient} rather than {@code TestRestTemplate}: the latter
 * needs {@code spring-boot-restclient}, which isn't otherwise a project dependency —
 * {@code ledger-accounts-service} never makes outbound REST calls, so it's not
 * worth pulling in just for these two tests. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local-noauth")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.grpc.server.port=0")
class NoAuthSecurityConfigTest {

    @LocalServerPort
    private int port;

    @Test
    void restEndpointIsReachableWithoutAuthenticationUnderLocalNoauthProfile() throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + port + "/v1/wallet?accountId=" + UUID.randomUUID()))
                        .GET().build(),
                HttpResponse.BodyHandlers.discarding());

        // 404, not 401: proves the request actually reached the use case (the
        // account is simply unknown) instead of being blocked upstream by security.
        assertThat(response.statusCode()).isEqualTo(404);
    }
}
