package dev.treyer.sagapay.ledger.config;

import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The other face of {@link NoAuthSecurityConfigTest}: without the {@code
 * local-noauth} profile, Spring Security's default lockdown must stay active — a
 * guard against a regression that disabled it by mistake before (ADR 0004: no
 * {@code SecurityFilterChain} was defined at all).
 *
 * <p>REST only — {@code spring-boot-starter-security-oauth2-resource-server} is a
 * Servlet/HTTP mechanism and can't cover the gRPC server, which is why {@code
 * GrpcDenyByDefaultInterceptor} exists as a separate, hand-written lock. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.grpc.server.port=0")
class DefaultRestSecurityLockdownTest {

    @LocalServerPort
    private int port;

    @Test
    void restEndpointRequiresAuthenticationByDefault() throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + port + "/v1/wallet?accountId=" + UUID.randomUUID()))
                        .GET().build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
