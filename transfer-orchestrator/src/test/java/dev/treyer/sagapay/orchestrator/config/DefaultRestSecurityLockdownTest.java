package dev.treyer.sagapay.orchestrator.config;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The other face of {@link NoAuthSecurityConfigTest}: without the {@code
 * local-noauth} profile, Spring Security's default lockdown must stay active. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DefaultRestSecurityLockdownTest {

    @LocalServerPort
    private int port;

    @Test
    void restEndpointRequiresAuthenticationByDefault() throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + port + "/v1/transfers/" + UUID.randomUUID()))
                        .GET().build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
